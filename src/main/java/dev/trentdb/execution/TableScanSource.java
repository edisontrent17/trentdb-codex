package dev.trentdb.execution;

import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.storage.StorageManager;

final class TableScanSource implements ChunkSource {
    private final StorageManager storageManager;
    private final TableCatalogEntry table;

    TableScanSource(StorageManager storageManager, TableCatalogEntry table) {
        this.storageManager = storageManager;
        this.table = table;
    }

    @Override
    public void execute(ChunkConsumer consumer) {
        for (var chunk : storageManager.getTable(table).scanChunks()) {
            consumer.accept(chunk);
        }
    }
}
