package dev.trentdb.execution.physical;

import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.StorageManager;

public final class PhysicalTableScan implements PhysicalSource {
    private final StorageManager storageManager;
    private final BoundTableRef tableRef;

    public PhysicalTableScan(StorageManager storageManager, BoundTableRef tableRef) {
        this.storageManager = storageManager;
        this.tableRef = tableRef;
    }

    public BoundTableRef tableRef() {
        return tableRef;
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        var chunks = tableRef.isReplacementScan()
                ? tableRef.replacementScan().scanFunction().scan()
                : storageManager.getTable(tableRef.table()).scanChunks();
        for (var chunk : chunks) {
            consumer.accept(chunk);
        }
    }
}
