package dev.trentdb.execution.physical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalNestedLoopJoin implements PhysicalOperator {
    private final StorageManager storageManager;
    private final List<String> leftNames;
    private final List<LogicalType> leftTypes;
    private final BoundTableRef right;
    private final BoundExpression condition;
    private final BoundExpression rightFilter;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalNestedLoopJoin(
            StorageManager storageManager,
            List<String> leftNames,
            List<LogicalType> leftTypes,
            BoundTableRef right,
            BoundExpression condition,
            BoundExpression rightFilter
    ) {
        this.storageManager = storageManager;
        this.leftNames = List.copyOf(leftNames);
        this.leftTypes = List.copyOf(leftTypes);
        this.right = right;
        this.condition = condition;
        this.rightFilter = rightFilter;
    }

    public BoundTableRef right() {
        return right;
    }

    public BoundExpression condition() {
        return condition;
    }

    public BoundExpression rightFilter() {
        return rightFilter;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.NESTED_LOOP_JOIN;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        List<ColumnCatalogEntry> rightColumns = columns(right);
        List<String> outputNames = outputNames(rightColumns);
        List<LogicalType> outputTypes = outputTypes(rightColumns);
        List<DataChunk> rightChunks = filterChunks(scanChunks(right), rightFilter);
        return new NestedLoopJoinLocalState(
                rightChunks,
                outputNames,
                outputTypes,
                singleRowChunk(outputNames, outputTypes)
        );
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        NestedLoopJoinLocalState state = (NestedLoopJoinLocalState) operatorInput.localState();
        List<Vector> outputVectors = createVectors(state.outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
        int bufferedCount = 0;

        for (DataChunk rightChunk : state.rightChunks) {
            for (int leftIndex = 0; leftIndex < input.cardinality(); leftIndex++) {
                for (int rightIndex = 0; rightIndex < rightChunk.cardinality(); rightIndex++) {
                    writeJoinedValues(state.conditionRow.vectors(), 0, input, leftIndex, rightChunk, rightIndex);
                    if (matches(state.conditionRow)) {
                        writeJoinedValues(outputVectors, bufferedCount, input, leftIndex, rightChunk, rightIndex);
                        bufferedCount++;
                        if (bufferedCount >= InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
                            downstream.accept(new DataChunk(state.outputNames, outputVectors));
                            outputVectors = createVectors(state.outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
                            bufferedCount = 0;
                        }
                    }
                }
            }
        }

        if (bufferedCount > 0) {
            downstream.accept(compactChunk(state.outputNames, state.outputTypes, outputVectors, bufferedCount));
        }
    }

    private List<DataChunk> scanChunks(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().scanFunction().scan();
        }
        return storageManager.getTable(tableRef.table()).scanChunks();
    }

    private List<DataChunk> filterChunks(List<DataChunk> inputChunks, BoundExpression predicate) {
        if (predicate == null) {
            return inputChunks;
        }
        ArrayList<DataChunk> filtered = new ArrayList<>(inputChunks.size());
        for (DataChunk chunk : inputChunks) {
            DataChunk filteredChunk = filterChunk(chunk, predicate);
            if (filteredChunk.cardinality() > 0) {
                filtered.add(filteredChunk);
            }
        }
        return List.copyOf(filtered);
    }

    private DataChunk filterChunk(DataChunk input, BoundExpression predicate) {
        Vector predicateVector = expressionExecutor.execute(predicate, input);
        if (!predicateVector.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Predicate did not evaluate to BOOLEAN");
        }
        SelectionVector selection = new SelectionVector(input.cardinality());
        int selectedCount = 0;
        for (int index = 0; index < input.cardinality(); index++) {
            if (predicateVector.isNull(index)) {
                continue;
            }
            if (predicateVector.getBoolean(index)) {
                selection.setIndex(selectedCount, index);
                selectedCount++;
            }
        }
        if (selectedCount == input.cardinality()) {
            return input;
        }
        return input.slice(selection, selectedCount);
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().columns();
        }
        return tableRef.table().columns();
    }

    private List<String> outputNames(List<ColumnCatalogEntry> rightColumns) {
        ArrayList<String> names = new ArrayList<>(leftNames.size() + rightColumns.size());
        names.addAll(leftNames);
        for (ColumnCatalogEntry column : rightColumns) {
            names.add(column.name());
        }
        return names;
    }

    private List<LogicalType> outputTypes(List<ColumnCatalogEntry> rightColumns) {
        ArrayList<LogicalType> types = new ArrayList<>(leftTypes.size() + rightColumns.size());
        types.addAll(leftTypes);
        for (ColumnCatalogEntry column : rightColumns) {
            types.add(column.logicalType());
        }
        return types;
    }

    private boolean matches(DataChunk rowChunk) {
        Vector valueVector = expressionExecutor.execute(condition, rowChunk);
        if (valueVector.isNull(0)) {
            return false;
        }
        if (!valueVector.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Join condition must evaluate to BOOLEAN");
        }
        return valueVector.getBoolean(0);
    }

    private void writeJoinedValues(
            List<Vector> target,
            int targetIndex,
            DataChunk leftChunk,
            int leftIndex,
            DataChunk rightChunk,
            int rightIndex
    ) {
        for (int columnIndex = 0; columnIndex < leftChunk.vectors().size(); columnIndex++) {
            target.get(columnIndex).copyFrom(targetIndex, leftChunk.column(columnIndex), leftIndex);
        }
        int rightOffset = leftChunk.vectors().size();
        for (int columnIndex = 0; columnIndex < rightChunk.vectors().size(); columnIndex++) {
            target.get(rightOffset + columnIndex).copyFrom(targetIndex, rightChunk.column(columnIndex), rightIndex);
        }
    }

    private DataChunk singleRowChunk(List<String> names, List<LogicalType> types) {
        return new DataChunk(names, createVectors(types, 1));
    }

    private List<Vector> createVectors(List<LogicalType> types, int cardinality) {
        ArrayList<Vector> vectors = new ArrayList<>(types.size());
        for (LogicalType type : types) {
            vectors.add(new Vector(type, cardinality));
        }
        return vectors;
    }

    private DataChunk compactChunk(List<String> names, List<LogicalType> types, List<Vector> sourceVectors, int cardinality) {
        List<Vector> vectors = createVectors(types, cardinality);
        for (int columnIndex = 0; columnIndex < vectors.size(); columnIndex++) {
            Vector vector = vectors.get(columnIndex);
            for (int rowIndex = 0; rowIndex < cardinality; rowIndex++) {
                vector.copyFrom(rowIndex, sourceVectors.get(columnIndex), rowIndex);
            }
        }
        return new DataChunk(names, vectors);
    }

    private static final class NestedLoopJoinLocalState extends LocalOperatorState {
        private final List<DataChunk> rightChunks;
        private final List<String> outputNames;
        private final List<LogicalType> outputTypes;
        private final DataChunk conditionRow;

        private NestedLoopJoinLocalState(
                List<DataChunk> rightChunks,
                List<String> outputNames,
                List<LogicalType> outputTypes,
                DataChunk conditionRow
        ) {
            this.rightChunks = List.copyOf(rightChunks);
            this.outputNames = List.copyOf(outputNames);
            this.outputTypes = List.copyOf(outputTypes);
            this.conditionRow = conditionRow;
        }
    }
}
