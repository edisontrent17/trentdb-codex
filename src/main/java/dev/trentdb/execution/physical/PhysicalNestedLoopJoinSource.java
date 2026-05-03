package dev.trentdb.execution.physical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
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

public final class PhysicalNestedLoopJoinSource implements PhysicalSource {
    private final StorageManager storageManager;
    private final BoundTableRef left;
    private final BoundTableRef right;
    private final BoundExpression condition;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalNestedLoopJoinSource(
            StorageManager storageManager,
            BoundTableRef left,
            BoundTableRef right,
            BoundExpression condition
    ) {
        this.storageManager = storageManager;
        this.left = left;
        this.right = right;
        this.condition = condition;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.NESTED_LOOP_JOIN;
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        List<ColumnCatalogEntry> leftColumns = columns(left);
        List<ColumnCatalogEntry> rightColumns = columns(right);
        List<String> outputNames = outputNames(leftColumns, rightColumns);
        List<LogicalType> outputTypes = outputTypes(leftColumns, rightColumns);
        List<DataChunk> leftChunks = scanChunks(left);
        List<DataChunk> rightChunks = scanChunks(right);
        List<Vector> outputVectors = createVectors(outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
        int bufferedCount = 0;
        DataChunk conditionRow = singleRowChunk(outputNames, outputTypes);

        for (DataChunk leftChunk : leftChunks) {
            for (DataChunk rightChunk : rightChunks) {
                for (int leftIndex = 0; leftIndex < leftChunk.cardinality(); leftIndex++) {
                    for (int rightIndex = 0; rightIndex < rightChunk.cardinality(); rightIndex++) {
                        writeJoinedValues(conditionRow.vectors(), 0, leftChunk, leftIndex, rightChunk, rightIndex);
                        if (matches(conditionRow)) {
                            writeJoinedValues(outputVectors, bufferedCount, leftChunk, leftIndex, rightChunk, rightIndex);
                            bufferedCount++;
                            if (bufferedCount >= InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
                                consumer.accept(new DataChunk(outputNames, outputVectors));
                                outputVectors = createVectors(outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
                                bufferedCount = 0;
                            }
                        }
                    }
                }
            }
        }

        if (bufferedCount > 0) {
            consumer.accept(compactChunk(outputNames, outputTypes, outputVectors, bufferedCount));
        }
    }

    private List<DataChunk> scanChunks(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().scanFunction().scan();
        }
        return storageManager.getTable(tableRef.table()).scanChunks();
    }

    private List<ColumnCatalogEntry> columns(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().columns();
        }
        return tableRef.table().columns();
    }

    private List<String> outputNames(List<ColumnCatalogEntry> leftColumns, List<ColumnCatalogEntry> rightColumns) {
        ArrayList<String> names = new ArrayList<>(leftColumns.size() + rightColumns.size());
        for (ColumnCatalogEntry column : leftColumns) {
            names.add(column.name());
        }
        for (ColumnCatalogEntry column : rightColumns) {
            names.add(column.name());
        }
        return names;
    }

    private List<LogicalType> outputTypes(List<ColumnCatalogEntry> leftColumns, List<ColumnCatalogEntry> rightColumns) {
        ArrayList<LogicalType> types = new ArrayList<>(leftColumns.size() + rightColumns.size());
        for (ColumnCatalogEntry column : leftColumns) {
            types.add(column.logicalType());
        }
        for (ColumnCatalogEntry column : rightColumns) {
            types.add(column.logicalType());
        }
        return types;
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

    private DataChunk singleRowChunk(List<String> names, List<LogicalType> types) {
        ArrayList<Vector> vectors = new ArrayList<>(types.size());
        for (int columnIndex = 0; columnIndex < types.size(); columnIndex++) {
            Vector vector = new Vector(types.get(columnIndex), 1);
            vectors.add(vector);
        }
        return new DataChunk(names, vectors);
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
}
