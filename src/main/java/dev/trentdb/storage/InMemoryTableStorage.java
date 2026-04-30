package dev.trentdb.storage;

import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InMemoryTableStorage {
    public static final int STANDARD_VECTOR_SIZE = 1024;

    private final TableCatalogEntry table;
    private final List<List<Object>> rows = new ArrayList<>();

    InMemoryTableStorage(TableCatalogEntry table) {
        this.table = table;
    }

    public void appendRow(List<Object> values) {
        if (values.size() != table.columns().size()) {
            throw new StorageException("Table " + table.name() + " expects " + table.columns().size()
                    + " values but got " + values.size());
        }
        rows.add(Collections.unmodifiableList(new ArrayList<>(values)));
    }

    public List<DataChunk> scanChunks() {
        var chunks = new ArrayList<DataChunk>();
        for (int offset = 0; offset < rows.size(); offset += STANDARD_VECTOR_SIZE) {
            chunks.add(chunk(offset, Math.min(STANDARD_VECTOR_SIZE, rows.size() - offset)));
        }
        return chunks;
    }

    private DataChunk chunk(int offset, int size) {
        var names = table.columns().stream().map(column -> column.name()).toList();
        var vectors = new ArrayList<Vector>(table.columns().size());
        for (var column : table.columns()) {
            var vector = new Vector(column.logicalType(), size);
            for (int rowIndex = 0; rowIndex < size; rowIndex++) {
                vector.set(rowIndex, rows.get(offset + rowIndex).get(column.ordinal()));
            }
            vectors.add(vector);
        }
        return new DataChunk(names, vectors);
    }
}
