package dev.trentdb.execution.physical;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionProfiler;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.StorageManager;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalTableScan implements PhysicalSource {
    private final StorageManager storageManager;
    private final BoundTableRef tableRef;
    private final List<Integer> projectedOrdinals;

    public PhysicalTableScan(StorageManager storageManager, BoundTableRef tableRef) {
        this(storageManager, tableRef, allOrdinals(tableRef));
    }

    public PhysicalTableScan(StorageManager storageManager, BoundTableRef tableRef, List<Integer> projectedOrdinals) {
        this.storageManager = storageManager;
        this.tableRef = tableRef;
        this.projectedOrdinals = List.copyOf(projectedOrdinals);
    }

    public BoundTableRef tableRef() {
        return tableRef;
    }

    public List<Integer> projectedOrdinals() {
        return projectedOrdinals;
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
        chunks = projectChunks(chunks, projectedOrdinals);
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

    private static List<Integer> allOrdinals(BoundTableRef tableRef) {
        int columnCount = tableRef.isReplacementScan()
                ? tableRef.replacementScan().columns().size()
                : tableRef.table().columns().size();
        ArrayList<Integer> ordinals = new ArrayList<>(columnCount);
        for (int index = 0; index < columnCount; index++) {
            ordinals.add(index);
        }
        return List.copyOf(ordinals);
    }

    static List<ColumnCatalogEntry> projectColumns(
            List<ColumnCatalogEntry> columns,
            List<Integer> projectedOrdinals
    ) {
        ArrayList<ColumnCatalogEntry> projected = new ArrayList<>(projectedOrdinals.size());
        for (int index = 0; index < projectedOrdinals.size(); index++) {
            ColumnCatalogEntry column = columns.get(projectedOrdinals.get(index));
            projected.add(new ColumnCatalogEntry(column.name(), column.logicalType(), index));
        }
        return List.copyOf(projected);
    }

    static List<DataChunk> projectChunks(List<DataChunk> chunks, List<Integer> projectedOrdinals) {
        if (projectsAllColumns(chunks, projectedOrdinals)) {
            return chunks;
        }
        ArrayList<DataChunk> projected = new ArrayList<>(chunks.size());
        for (DataChunk chunk : chunks) {
            projected.add(projectChunk(chunk, projectedOrdinals));
        }
        return List.copyOf(projected);
    }

    private static boolean projectsAllColumns(List<DataChunk> chunks, List<Integer> projectedOrdinals) {
        if (chunks.isEmpty()) {
            return true;
        }
        if (chunks.getFirst().vectors().size() != projectedOrdinals.size()) {
            return false;
        }
        for (int index = 0; index < projectedOrdinals.size(); index++) {
            if (projectedOrdinals.get(index) != index) {
                return false;
            }
        }
        return true;
    }

    private static DataChunk projectChunk(DataChunk chunk, List<Integer> projectedOrdinals) {
        ArrayList<String> names = new ArrayList<>(projectedOrdinals.size());
        ArrayList<Vector> vectors = new ArrayList<>(projectedOrdinals.size());
        for (Integer ordinal : projectedOrdinals) {
            names.add(chunk.names().get(ordinal));
            vectors.add(chunk.column(ordinal));
        }
        return new DataChunk(names, vectors);
    }
}
