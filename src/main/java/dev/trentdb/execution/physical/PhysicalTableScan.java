package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.ExecutionProfiler;
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
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.TABLE_SCAN;
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        long scanStart = ExecutionProfiler.start();
        List<DataChunk> chunks = tableRef.isReplacementScan()
                ? tableRef.replacementScan().scanFunction().scan()
                : storageManager.getTable(tableRef.table()).scanChunks();
        int rowCount = 0;
        for (DataChunk chunk : chunks) {
            rowCount += chunk.cardinality();
        }
        ExecutionProfiler.log(
                "PhysicalTableScan",
                "load",
                scanStart,
                "replacement=" + tableRef.isReplacementScan() + " chunks=" + chunks.size() + " rows=" + rowCount
        );
        long emitStart = ExecutionProfiler.start();
        for (DataChunk chunk : chunks) {
            consumer.accept(chunk);
        }
        ExecutionProfiler.log(
                "PhysicalTableScan",
                "emit",
                emitStart,
                "chunks=" + chunks.size() + " rows=" + rowCount
        );
    }
}
