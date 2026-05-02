package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.StorageManager;

import java.util.List;

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
        List<DataChunk> chunks = tableRef.isReplacementScan()
                ? tableRef.replacementScan().scanFunction().scan()
                : storageManager.getTable(tableRef.table()).scanChunks();
        for (DataChunk chunk : chunks) {
            consumer.accept(chunk);
        }
    }
}
