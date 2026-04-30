package dev.trentdb.storage;

import dev.trentdb.catalog.TableCatalogEntry;

import java.util.IdentityHashMap;
import java.util.Map;

public final class StorageManager {
    private final Map<TableCatalogEntry, InMemoryTableStorage> tables = new IdentityHashMap<>();

    public InMemoryTableStorage createTable(TableCatalogEntry table) {
        var storage = new InMemoryTableStorage(table);
        var previous = tables.putIfAbsent(table, storage);
        if (previous != null) {
            throw new StorageException("Storage already exists for table: " + table.name());
        }
        return storage;
    }

    public InMemoryTableStorage getTable(TableCatalogEntry table) {
        var storage = tables.get(table);
        if (storage == null) {
            throw new StorageException("Storage not found for table: " + table.name());
        }
        return storage;
    }
}
