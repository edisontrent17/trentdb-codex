package dev.trentdb.storage;

import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryTableStorage {
    public static final int STANDARD_VECTOR_SIZE = 1024;

    private final TableCatalogEntry table;
    private final List<String> columnNames;
    private final List<LogicalType> columnTypes;
    private final List<DataChunk> sealedChunks = new ArrayList<>();
    private List<Vector> appendVectors;
    private int appendCount;

    InMemoryTableStorage(TableCatalogEntry table) {
        this.table = table;
        this.columnNames = table.columns().stream().map(column -> column.name()).toList();
        this.columnTypes = table.columns().stream().map(column -> column.logicalType()).toList();
        this.appendVectors = allocateVectors(STANDARD_VECTOR_SIZE);
        this.appendCount = 0;
    }

    public void appendRow(List<Object> values) {
        if (values.size() != table.columns().size()) {
            throw new StorageException("Table " + table.name() + " expects " + table.columns().size()
                    + " values but got " + values.size());
        }
        for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
            writeValue(
                    appendVectors.get(columnIndex),
                    appendCount,
                    values.get(columnIndex),
                    columnTypes.get(columnIndex)
            );
        }
        appendCount++;
        if (appendCount >= STANDARD_VECTOR_SIZE) {
            sealedChunks.add(new DataChunk(columnNames, appendVectors));
            appendVectors = allocateVectors(STANDARD_VECTOR_SIZE);
            appendCount = 0;
        }
    }

    public List<DataChunk> scanChunks() {
        ArrayList<DataChunk> chunks = new ArrayList<>(sealedChunks.size() + (appendCount > 0 ? 1 : 0));
        chunks.addAll(sealedChunks);
        if (appendCount > 0) {
            chunks.add(compactChunk(appendVectors, appendCount));
        }
        return chunks;
    }

    private DataChunk compactChunk(List<Vector> sourceVectors, int cardinality) {
        List<Vector> vectors = allocateVectors(cardinality);
        for (int columnIndex = 0; columnIndex < vectors.size(); columnIndex++) {
            Vector targetVector = vectors.get(columnIndex);
            Vector sourceVector = sourceVectors.get(columnIndex);
            for (int rowIndex = 0; rowIndex < cardinality; rowIndex++) {
                targetVector.copyFrom(rowIndex, sourceVector, rowIndex);
            }
        }
        return new DataChunk(columnNames, vectors);
    }

    private List<Vector> allocateVectors(int cardinality) {
        ArrayList<Vector> vectors = new ArrayList<>(columnTypes.size());
        for (LogicalType columnType : columnTypes) {
            vectors.add(new Vector(columnType, cardinality));
        }
        return vectors;
    }

    private void writeValue(Vector vector, int rowIndex, Object value, LogicalType logicalType) {
        if (value == null) {
            vector.setNull(rowIndex);
            return;
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            vector.setBoolean(rowIndex, (Boolean) value);
            return;
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            vector.setInteger(rowIndex, ((Number) value).intValue());
            return;
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            vector.setBigint(rowIndex, ((Number) value).longValue());
            return;
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            vector.setDouble(rowIndex, ((Number) value).doubleValue());
            return;
        }
        if (logicalType.equals(LogicalType.TEXT)) {
            vector.setText(rowIndex, (String) value);
            return;
        }
        if (logicalType.equals(LogicalType.DATE)) {
            vector.setDate(rowIndex, (LocalDate) value);
            return;
        }
        vector.setNull(rowIndex);
    }
}
