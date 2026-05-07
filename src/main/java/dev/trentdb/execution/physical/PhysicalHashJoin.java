package dev.trentdb.execution.physical;

import dev.trentdb.ast.JoinType;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExecutionProfiler;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalHashJoin implements PhysicalOperator {
    private final StorageManager storageManager;
    private final List<String> leftNames;
    private final List<LogicalType> leftTypes;
    private final BoundTableRef right;
    private final JoinType joinType;
    private final int leftKeyOrdinal;
    private final int rightKeyOrdinal;
    private final BoundExpression rightFilter;
    private final BoundExpression residualFilter;
    private final ExpressionExecutor expressionExecutor;

    public PhysicalHashJoin(
            StorageManager storageManager,
            List<String> leftNames,
            List<LogicalType> leftTypes,
            BoundTableRef right,
            JoinType joinType,
            int leftKeyOrdinal,
            int rightKeyOrdinal,
            BoundExpression rightFilter,
            BoundExpression residualFilter,
            ExpressionExecutor expressionExecutor
    ) {
        this.storageManager = storageManager;
        this.leftNames = List.copyOf(leftNames);
        this.leftTypes = List.copyOf(leftTypes);
        this.right = right;
        this.joinType = joinType;
        this.leftKeyOrdinal = leftKeyOrdinal;
        this.rightKeyOrdinal = rightKeyOrdinal;
        this.rightFilter = rightFilter;
        this.residualFilter = residualFilter;
        this.expressionExecutor = expressionExecutor;
    }

    public BoundTableRef right() {
        return right;
    }

    public JoinType joinType() {
        return joinType;
    }

    public int leftKeyOrdinal() {
        return leftKeyOrdinal;
    }

    public int rightKeyOrdinal() {
        return rightKeyOrdinal;
    }

    public BoundExpression rightFilter() {
        return rightFilter;
    }

    public BoundExpression residualFilter() {
        return residualFilter;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.HASH_JOIN;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        List<ColumnCatalogEntry> rightColumns = columns(right);
        List<String> outputNames = outputNames(rightColumns);
        List<LogicalType> outputTypes = outputTypes(rightColumns);
        long rightScanStart = ExecutionProfiler.start();
        List<DataChunk> rightChunks = filterChunks(scanChunks(right), rightFilter, "filter_right");
        ExecutionProfiler.log(
                "PhysicalHashJoin",
                "scan_right",
                rightScanStart,
                "chunks=" + rightChunks.size() + " rows=" + countRows(rightChunks)
        );
        LogicalType keyType = rightColumns.get(rightKeyOrdinal).logicalType();
        long buildStart = ExecutionProfiler.start();
        BuildIndex buildIndex = buildRightIndex(rightChunks, keyType);
        ExecutionProfiler.log(
                "PhysicalHashJoin",
                "build_index",
                buildStart,
                "entries=" + buildIndex.size() + " keyType=" + keyType.id().name()
        );
        DataChunk residualRow = residualFilter == null ? null : singleRowChunk(outputNames, outputTypes);
        return new HashJoinLocalState(rightChunks, buildIndex, keyType, outputNames, outputTypes, residualRow);
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        HashJoinLocalState state = (HashJoinLocalState) operatorInput.localState();
        Vector leftKeyVector = input.column(leftKeyOrdinal);
        List<Vector> outputVectors = createVectors(state.outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
        int bufferedCount = 0;

        for (int leftRowIndex = 0; leftRowIndex < input.cardinality(); leftRowIndex++) {
            boolean matched = false;
            if (!leftKeyVector.isNull(leftRowIndex)) {
                long probeKey = keyAsLong(leftKeyVector, leftRowIndex, state.keyType);
                int match = state.buildIndex.firstEntry(probeKey);
                for (int entry = match; entry >= 0; entry = state.buildIndex.nextEntry(entry, probeKey)) {
                    DataChunk rightChunk = state.rightChunks.get(state.buildIndex.chunkIndex(entry));
                    int rightRowIndex = state.buildIndex.rowIndex(entry);
                    if (state.residualRow != null) {
                        writeJoinedValues(state.residualRow.vectors(), 0, input, leftRowIndex, rightChunk, rightRowIndex);
                        if (!matchesResidual(state.residualRow)) {
                            continue;
                        }
                        copyResidualRow(outputVectors, bufferedCount, state.residualRow);
                    } else {
                        writeJoinedValues(outputVectors, bufferedCount, input, leftRowIndex, rightChunk, rightRowIndex);
                    }
                    matched = true;
                    bufferedCount++;
                    if (bufferedCount >= InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
                        downstream.accept(new DataChunk(state.outputNames, outputVectors));
                        outputVectors = createVectors(state.outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
                        bufferedCount = 0;
                    }
                }
            }
            if (!matched && joinType == JoinType.LEFT) {
                writeLeftWithNullRight(outputVectors, bufferedCount, input, leftRowIndex);
                bufferedCount++;
                if (bufferedCount >= InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
                    downstream.accept(new DataChunk(state.outputNames, outputVectors));
                    outputVectors = createVectors(state.outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
                    bufferedCount = 0;
                }
            }
        }

        if (bufferedCount > 0) {
            downstream.accept(compactChunk(state.outputNames, state.outputTypes, outputVectors, bufferedCount));
        }
    }

    private void writeLeftWithNullRight(List<Vector> target, int targetIndex, DataChunk leftChunk, int leftIndex) {
        for (int columnIndex = 0; columnIndex < leftChunk.vectors().size(); columnIndex++) {
            target.get(columnIndex).copyFrom(targetIndex, leftChunk.column(columnIndex), leftIndex);
        }
        for (int columnIndex = leftChunk.vectors().size(); columnIndex < target.size(); columnIndex++) {
            target.get(columnIndex).setNull(targetIndex);
        }
    }

    private List<DataChunk> scanChunks(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().scanFunction().scan();
        }
        return storageManager.getTable(tableRef.table()).scanChunks();
    }

    private List<DataChunk> filterChunks(List<DataChunk> inputChunks, BoundExpression predicate, String profileEvent) {
        if (predicate == null) {
            return inputChunks;
        }
        long filterStart = ExecutionProfiler.start();
        ArrayList<DataChunk> filtered = new ArrayList<>(inputChunks.size());
        int inputRows = 0;
        int outputRows = 0;
        for (DataChunk chunk : inputChunks) {
            inputRows += chunk.cardinality();
            DataChunk filteredChunk = filterChunk(chunk, predicate);
            if (filteredChunk.cardinality() > 0) {
                filtered.add(filteredChunk);
                outputRows += filteredChunk.cardinality();
            }
        }
        ExecutionProfiler.log(
                "PhysicalHashJoin",
                profileEvent,
                filterStart,
                "inputRows=" + inputRows + " outputRows=" + outputRows + " chunks=" + filtered.size()
        );
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

    private int countRows(List<DataChunk> chunks) {
        int rowCount = 0;
        for (DataChunk chunk : chunks) {
            rowCount += chunk.cardinality();
        }
        return rowCount;
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

    private BuildIndex buildRightIndex(List<DataChunk> rightChunks, LogicalType keyType) {
        int rowCapacity = 0;
        for (DataChunk chunk : rightChunks) {
            rowCapacity += chunk.cardinality();
        }
        BuildIndex index = new BuildIndex(rowCapacity);
        for (int chunkIndex = 0; chunkIndex < rightChunks.size(); chunkIndex++) {
            DataChunk chunk = rightChunks.get(chunkIndex);
            Vector keyVector = chunk.column(rightKeyOrdinal);
            for (int rowIndex = 0; rowIndex < chunk.cardinality(); rowIndex++) {
                if (!keyVector.isNull(rowIndex)) {
                    index.insert(keyAsLong(keyVector, rowIndex, keyType), chunkIndex, rowIndex);
                }
            }
        }
        return index;
    }

    private long keyAsLong(Vector keyVector, int rowIndex, LogicalType keyType) {
        if (keyType.equals(LogicalType.BOOLEAN)) {
            return keyVector.getBoolean(rowIndex) ? 1L : 0L;
        }
        if (keyType.equals(LogicalType.INTEGER)) {
            return keyVector.getInteger(rowIndex);
        }
        if (keyType.equals(LogicalType.BIGINT)) {
            return keyVector.getBigint(rowIndex);
        }
        if (keyType.equals(LogicalType.DATE)) {
            return keyVector.getDate(rowIndex).toEpochDay();
        }
        throw new ExecutionException("Unsupported HASH JOIN key type: " + keyType.id().name());
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

    private boolean matchesResidual(DataChunk rowChunk) {
        Vector valueVector = expressionExecutor.execute(residualFilter, rowChunk);
        if (valueVector.isNull(0)) {
            return false;
        }
        if (!valueVector.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Join residual predicate must evaluate to BOOLEAN");
        }
        return valueVector.getBoolean(0);
    }

    private void copyResidualRow(List<Vector> target, int targetIndex, DataChunk residualRow) {
        for (int columnIndex = 0; columnIndex < residualRow.vectors().size(); columnIndex++) {
            target.get(columnIndex).copyFrom(targetIndex, residualRow.column(columnIndex), 0);
        }
    }

    private List<Vector> createVectors(List<LogicalType> types, int cardinality) {
        ArrayList<Vector> vectors = new ArrayList<>(types.size());
        for (LogicalType type : types) {
            vectors.add(new Vector(type, cardinality));
        }
        return vectors;
    }

    private DataChunk singleRowChunk(List<String> names, List<LogicalType> types) {
        return new DataChunk(names, createVectors(types, 1));
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

    private static final class HashJoinLocalState extends LocalOperatorState {
        private final List<DataChunk> rightChunks;
        private final BuildIndex buildIndex;
        private final LogicalType keyType;
        private final List<String> outputNames;
        private final List<LogicalType> outputTypes;
        private final DataChunk residualRow;

        private HashJoinLocalState(
                List<DataChunk> rightChunks,
                BuildIndex buildIndex,
                LogicalType keyType,
                List<String> outputNames,
                List<LogicalType> outputTypes,
                DataChunk residualRow
        ) {
            this.rightChunks = List.copyOf(rightChunks);
            this.buildIndex = buildIndex;
            this.keyType = keyType;
            this.outputNames = List.copyOf(outputNames);
            this.outputTypes = List.copyOf(outputTypes);
            this.residualRow = residualRow;
        }
    }

    private static final class BuildIndex {
        private final int[] bucketHeads;
        private final int[] next;
        private final long[] keys;
        private final int[] chunkIndices;
        private final int[] rowIndices;
        private final int bucketMask;
        private int size;

        private BuildIndex(int rowCapacity) {
            int safeCapacity = Math.max(1, rowCapacity);
            int bucketCount = nextPowerOfTwo(Math.max(16, safeCapacity * 2));
            this.bucketHeads = new int[bucketCount];
            this.next = new int[safeCapacity];
            this.keys = new long[safeCapacity];
            this.chunkIndices = new int[safeCapacity];
            this.rowIndices = new int[safeCapacity];
            this.bucketMask = bucketCount - 1;
            this.size = 0;
            for (int index = 0; index < bucketHeads.length; index++) {
                bucketHeads[index] = -1;
            }
        }

        private void insert(long key, int chunkIndex, int rowIndex) {
            int entry = size;
            size++;
            if (entry >= keys.length) {
                throw new ExecutionException("HASH JOIN build index exceeded expected row capacity");
            }
            int bucket = bucket(key);
            keys[entry] = key;
            chunkIndices[entry] = chunkIndex;
            rowIndices[entry] = rowIndex;
            next[entry] = bucketHeads[bucket];
            bucketHeads[bucket] = entry;
        }

        private int firstEntry(long key) {
            int entry = bucketHeads[bucket(key)];
            while (entry >= 0) {
                if (keys[entry] == key) {
                    return entry;
                }
                entry = next[entry];
            }
            return -1;
        }

        private int nextEntry(int currentEntry, long key) {
            int entry = next[currentEntry];
            while (entry >= 0) {
                if (keys[entry] == key) {
                    return entry;
                }
                entry = next[entry];
            }
            return -1;
        }

        private int chunkIndex(int entry) {
            return chunkIndices[entry];
        }

        private int rowIndex(int entry) {
            return rowIndices[entry];
        }

        private int size() {
            return size;
        }

        private int bucket(long key) {
            return mix64(key) & bucketMask;
        }

        private int mix64(long key) {
            long z = key + 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            return (int) z;
        }

        private int nextPowerOfTwo(int value) {
            int result = 1;
            while (result < value) {
                result <<= 1;
            }
            return result;
        }
    }
}
