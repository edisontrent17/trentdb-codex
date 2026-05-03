package dev.trentdb.execution.physical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PhysicalHashJoinSource implements PhysicalSource {
    private final StorageManager storageManager;
    private final BoundTableRef left;
    private final BoundTableRef right;
    private final int leftKeyOrdinal;
    private final int rightKeyOrdinal;

    public PhysicalHashJoinSource(
            StorageManager storageManager,
            BoundTableRef left,
            BoundTableRef right,
            int leftKeyOrdinal,
            int rightKeyOrdinal
    ) {
        this.storageManager = storageManager;
        this.left = left;
        this.right = right;
        this.leftKeyOrdinal = leftKeyOrdinal;
        this.rightKeyOrdinal = rightKeyOrdinal;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.HASH_JOIN;
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        List<ColumnCatalogEntry> leftColumns = columns(left);
        List<ColumnCatalogEntry> rightColumns = columns(right);
        List<String> outputNames = outputNames(leftColumns, rightColumns);
        List<LogicalType> outputTypes = outputTypes(leftColumns, rightColumns);
        List<DataChunk> leftChunks = scanChunks(left);
        List<DataChunk> rightChunks = scanChunks(right);

        Map<HashKey, List<RowRef>> buildIndex = buildRightIndex(rightChunks);
        List<Vector> outputVectors = createVectors(outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
        int bufferedCount = 0;

        for (DataChunk leftChunk : leftChunks) {
            Vector leftKeyVector = leftChunk.column(leftKeyOrdinal);
            for (int leftRowIndex = 0; leftRowIndex < leftChunk.cardinality(); leftRowIndex++) {
                if (leftKeyVector.isNull(leftRowIndex)) {
                    continue;
                }
                HashKey probeKey = HashKey.fromVector(leftKeyVector, leftRowIndex);
                List<RowRef> matches = buildIndex.get(probeKey);
                if (matches == null) {
                    continue;
                }
                for (RowRef match : matches) {
                    DataChunk rightChunk = rightChunks.get(match.chunkIndex());
                    writeJoinedValues(outputVectors, bufferedCount, leftChunk, leftRowIndex, rightChunk, match.rowIndex());
                    bufferedCount++;
                    if (bufferedCount >= InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
                        consumer.accept(new DataChunk(outputNames, outputVectors));
                        outputVectors = createVectors(outputTypes, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
                        bufferedCount = 0;
                    }
                }
            }
        }

        if (bufferedCount > 0) {
            consumer.accept(compactChunk(outputNames, outputTypes, outputVectors, bufferedCount));
        }
    }

    private Map<HashKey, List<RowRef>> buildRightIndex(List<DataChunk> rightChunks) {
        HashMap<HashKey, List<RowRef>> index = new HashMap<>();
        for (int chunkIndex = 0; chunkIndex < rightChunks.size(); chunkIndex++) {
            DataChunk chunk = rightChunks.get(chunkIndex);
            Vector keyVector = chunk.column(rightKeyOrdinal);
            for (int rowIndex = 0; rowIndex < chunk.cardinality(); rowIndex++) {
                if (keyVector.isNull(rowIndex)) {
                    continue;
                }
                HashKey key = HashKey.fromVector(keyVector, rowIndex);
                List<RowRef> rows = index.computeIfAbsent(key, ignored -> new ArrayList<>());
                rows.add(new RowRef(chunkIndex, rowIndex));
            }
        }
        return index;
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

    private record RowRef(int chunkIndex, int rowIndex) {
    }

    private record HashKey(
            LogicalType logicalType,
            boolean booleanValue,
            int integerValue,
            long bigintValue,
            String textValue,
            LocalDate dateValue
    ) {
        private static HashKey fromVector(Vector vector, int index) {
            LogicalType type = vector.logicalType();
            if (type.equals(LogicalType.BOOLEAN)) {
                return new HashKey(type, vector.getBoolean(index), 0, 0L, null, null);
            }
            if (type.equals(LogicalType.INTEGER)) {
                return new HashKey(type, false, vector.getInteger(index), 0L, null, null);
            }
            if (type.equals(LogicalType.BIGINT)) {
                return new HashKey(type, false, 0, vector.getBigint(index), null, null);
            }
            if (type.equals(LogicalType.TEXT)) {
                return new HashKey(type, false, 0, 0L, vector.getText(index), null);
            }
            if (type.equals(LogicalType.DATE)) {
                return new HashKey(type, false, 0, 0L, null, vector.getDate(index));
            }
            throw new ExecutionException("Unsupported HASH JOIN key type: " + type.id().name());
        }
    }
}
