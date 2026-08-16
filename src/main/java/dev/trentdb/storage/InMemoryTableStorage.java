package dev.trentdb.storage;

import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.common.VectorSize;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;

public final class InMemoryTableStorage {
    public static final int STANDARD_VECTOR_SIZE = VectorSize.STANDARD_VECTOR_SIZE;

    private final TableCatalogEntry table;
    private final List<String> columnNames;
    private final List<LogicalType> columnTypes;
    private final List<DataChunk> sealedChunks = new ArrayList<>();
    private List<Vector> appendVectors;
    private int appendCount;
    private long nextRowId = 1;
    private final Map<dev.trentdb.transaction.Transaction, PendingRows> pendingAppends = new IdentityHashMap<>();
    private final List<VersionedRow> committedRows = new ArrayList<>();
    private final Map<dev.trentdb.transaction.Transaction, java.util.Set<Long>> pendingDeletes = new IdentityHashMap<>();
    private final Map<dev.trentdb.transaction.Transaction, PendingUpdates> pendingUpdates = new IdentityHashMap<>();

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

    private List<DataChunk> scanLegacyChunks() {
        ArrayList<DataChunk> chunks = new ArrayList<>(sealedChunks.size() + (appendCount > 0 ? 1 : 0));
        chunks.addAll(sealedChunks);
        if (appendCount > 0) {
            chunks.add(compactChunk(appendVectors, appendCount));
        }
        return chunks;
    }

    /** Stages a private append and allocates its monotonic logical row ID. */
    public synchronized long stageAppend(dev.trentdb.transaction.Transaction transaction, List<Object> values) { return stageAppend(transaction, values, nextRowId); }
    /** Recovery path: only the next deterministic row ID is accepted. */
    public synchronized long stageAppend(dev.trentdb.transaction.Transaction transaction, List<Object> values, long rowId) {
        validateValues(values); if (rowId != nextRowId) throw new StorageException("Row ID sequence divergence: expected " + nextRowId + " got " + rowId);
        pendingAppends.computeIfAbsent(transaction, ignored -> new PendingRows()).rows.add(new PendingRow(rowId, java.util.Collections.unmodifiableList(new ArrayList<>(values)))); nextRowId++; return rowId;
    }
    /** Recovery path: accepts an otherwise-unused durable row ID and preserves future monotonic allocation. */
    public synchronized long stageRecoveredAppend(dev.trentdb.transaction.Transaction transaction, List<Object> values, long rowId) {
        validateValues(values);
        if (rowById(rowId) != null || pendingRowExists(rowId)) throw new StorageException("Recovered row ID already exists: " + rowId);
        pendingAppends.computeIfAbsent(transaction, ignored -> new PendingRows()).rows.add(new PendingRow(rowId, java.util.Collections.unmodifiableList(new ArrayList<>(values))));
        nextRowId = Math.max(nextRowId, Math.addExact(rowId, 1));
        return rowId;
    }

    /** Recovery-only reservation; no row becomes visible. */
    public synchronized void reserveNextRowIdAtLeast(long next) {
        if (next <= 0) throw new IllegalArgumentException("Next row ID must be positive");
        if (next > nextRowId) nextRowId = next;
    }

    /** Validates then stages a complete INSERT statement atomically in statement order. */
    public synchronized List<Long> stageAppendBatch(dev.trentdb.transaction.Transaction transaction, List<List<Object>> rows) {
        if (rows.isEmpty()) throw new StorageException("INSERT requires at least one row"); for (var values : rows) validateValues(values); var pending = pendingAppends.computeIfAbsent(transaction, ignored -> new PendingRows()); var ids = new ArrayList<Long>(rows.size()); for (var values : rows) { long rowId = nextRowId++; pending.rows.add(new PendingRow(rowId, java.util.Collections.unmodifiableList(new ArrayList<>(values)))); ids.add(rowId); } return List.copyOf(ids);
    }
    /** Reverts the most recently staged statement batch without touching prior transaction appends. */
    public synchronized void discardAppendBatch(dev.trentdb.transaction.Transaction transaction, List<Long> ids) {
        if (ids.isEmpty()) return; var pending = pendingAppends.get(transaction); if (pending == null || pending.rows.size() < ids.size()) throw new StorageException("INSERT batch is not staged"); int start = pending.rows.size() - ids.size(); for (int index = 0; index < ids.size(); index++) if (pending.rows.get(start + index).rowId != ids.get(index)) throw new StorageException("INSERT batch is not the most recent staged append"); if (nextRowId != ids.getLast() + 1) throw new StorageException("Cannot rewind INSERT row IDs after a concurrent reservation"); pending.rows.subList(start, pending.rows.size()).clear(); nextRowId = ids.getFirst(); if (pending.rows.isEmpty()) pendingAppends.remove(transaction);
    }

    public synchronized void commitAppends(dev.trentdb.transaction.Transaction transaction, long commitVersion) {
        var pending = pendingAppends.get(transaction); if (pending == null) return; if (pending.committed) throw new StorageException("Transaction rows already committed");
        for (var row : pending.rows) committedRows.add(new VersionedRow(row.rowId, row.values, commitVersion, pending)); pending.committed = true;
    }
    /** Stages tombstones for every row visible to this transaction and returns sorted stable IDs. */
    public synchronized List<Long> stageDeleteAll(dev.trentdb.transaction.Transaction transaction) {
        long snapshot = transaction.snapshot().catalogVersion(); var ids = new ArrayList<Long>(); var alreadyDeleted = pendingDeletes.getOrDefault(transaction, java.util.Set.of());
        for (var row : committedRows) if (row.commitVersion <= snapshot && row.deleteVersion == Long.MAX_VALUE && !alreadyDeleted.contains(row.rowId)) ids.add(row.rowId);
        var pending = pendingAppends.get(transaction); if (pending != null) for (var row : pending.rows) if (!alreadyDeleted.contains(row.rowId)) ids.add(row.rowId); ids.sort(Long::compare); return stageDelete(transaction, ids);
    }
    /** Stages strictly sorted target row IDs visible in this transaction snapshot. */
    public synchronized List<Long> stageDelete(dev.trentdb.transaction.Transaction transaction, List<Long> ids) {
        long snapshot = transaction.snapshot().catalogVersion(); var targets = pendingDeletes.computeIfAbsent(transaction, ignored -> new java.util.TreeSet<>()); long previous = 0;
        for (long id : ids) { if (id <= previous) throw new StorageException("Delete row IDs must be strictly sorted"); previous = id; var row = rowById(id); if (row == null) { if (pendingRowById(transaction, id) == null) throw new StorageException("Delete target row is not visible: " + id); } else if (row.commitVersion > snapshot || row.deleteVersion != Long.MAX_VALUE) throw new StorageException("Delete target row is not visible: " + id); targets.add(id); } return List.copyOf(ids);
    }
    /** Stages immutable full-row replacement values without changing logical row identity. */
    public synchronized void stageUpdates(dev.trentdb.transaction.Transaction transaction, List<RowReplacement> replacements) {
        long snapshot = transaction.snapshot().catalogVersion(); var updates = pendingUpdates.computeIfAbsent(transaction, ignored -> new PendingUpdates()); long previous = 0;
        for (var replacement : replacements) { long id = replacement.rowId(); if (id <= previous) throw new StorageException("Update row IDs must be strictly sorted"); previous = id; validateValues(replacement.values()); var row = rowById(id); boolean visible = row != null ? row.commitVersion <= snapshot && (row.deleteVersion == Long.MAX_VALUE || row.deleteVersion > snapshot) && !pendingDeletes.getOrDefault(transaction, java.util.Set.of()).contains(id) : pendingRowById(transaction, id) != null && !pendingDeletes.getOrDefault(transaction, java.util.Set.of()).contains(id); if (!visible) throw new StorageException("Update target row is not visible: " + id); updates.rows.put(id, new PendingUpdate(replacement)); }
    }
    public synchronized void commitUpdates(dev.trentdb.transaction.Transaction transaction, long commitVersion) { var updates = pendingUpdates.get(transaction); if (updates == null) return; if (updates.committed) throw new StorageException("Transaction updates already committed"); for (var replacement : updates.rows.values()) { var row = rowById(replacement.rowId()); if (row == null) throw new StorageException("Update target row disappeared: " + replacement.rowId()); row.values.add(new RowValueVersion(replacement.values(), commitVersion, updates)); } updates.committed = true; }
    public synchronized void completeUpdates(dev.trentdb.transaction.Transaction transaction) { pendingUpdates.remove(transaction); }
    public synchronized void rollbackUpdates(dev.trentdb.transaction.Transaction transaction) { var updates = pendingUpdates.remove(transaction); if (updates != null && updates.committed) for (var row : committedRows) row.values.removeIf(version -> version.owner == updates); }

    public synchronized void commitDeletes(dev.trentdb.transaction.Transaction transaction, long commitVersion) { var ids = pendingDeletes.get(transaction); if (ids != null) for (long id : ids) { var row = rowById(id); if (row != null) row.deleteVersion = commitVersion; } }
    public synchronized void completeDeletes(dev.trentdb.transaction.Transaction transaction) { pendingDeletes.remove(transaction); }
    public synchronized void rollbackDeletes(dev.trentdb.transaction.Transaction transaction) { var ids = pendingDeletes.remove(transaction); if (ids != null) for (long id : ids) { var row = rowById(id); if (row != null && row.deleteVersion != Long.MAX_VALUE) row.deleteVersion = Long.MAX_VALUE; } }
    public synchronized void completeAppends(dev.trentdb.transaction.Transaction transaction) { pendingAppends.remove(transaction); }
    public synchronized void rollbackAppends(dev.trentdb.transaction.Transaction transaction) { var pending = pendingAppends.remove(transaction); if (pending != null && pending.committed) committedRows.removeIf(row -> row.owner == pending); }
    public synchronized List<DataChunk> scanChunks() { return scanChunks(null); }
    public synchronized List<DataChunk> scanChunks(dev.trentdb.transaction.Transaction transaction) {
        var chunks = new ArrayList<DataChunk>(scanLegacyChunks()); var visibleRows = new ArrayList<List<Object>>(); long snapshot = transaction == null ? Long.MAX_VALUE : transaction.snapshot().catalogVersion();
        for (var row : committedRows) if (row.commitVersion <= snapshot && (row.deleteVersion == Long.MAX_VALUE || row.deleteVersion > snapshot) && (transaction == null || !pendingDeletes.getOrDefault(transaction, java.util.Set.of()).contains(row.rowId))) { var pending = transaction == null ? null : pendingUpdateById(transaction, row.rowId); visibleRows.add(pending == null ? row.valuesAt(snapshot) : pending.values()); }
        if (transaction != null) { var pending = pendingAppends.get(transaction); if (pending != null) for (var row : pending.rows) if (!pendingDeletes.getOrDefault(transaction, java.util.Set.of()).contains(row.rowId)) { var replacement = pendingUpdateById(transaction, row.rowId); visibleRows.add(replacement == null ? row.values : replacement.values()); } }
        if (!visibleRows.isEmpty()) { var temporary = new InMemoryTableStorage(table); for (var row : visibleRows) temporary.appendRow(row); chunks.addAll(temporary.scanLegacyChunks()); } return List.copyOf(chunks);
    }
    private void validateValues(List<Object> values) { if (values.size() != table.columns().size()) throw new StorageException("Table " + table.name() + " expects " + table.columns().size() + " values but got " + values.size()); }
    private boolean pendingRowExists(long rowId) { for (var pending : pendingAppends.values()) for (var row : pending.rows) if (row.rowId == rowId) return true; return false; }

    /**
     * Export-only committed snapshot. This bypasses neither MVCC nor transaction state: callers
     * must supply a read transaction and no staged rows, updates, deletes, or legacy test chunks
     * may be present.
     */
    public synchronized List<List<Object>> exportCommittedRows(dev.trentdb.transaction.Transaction transaction) {
        if (transaction == null) throw new IllegalArgumentException("DuckDB V2 export requires a transaction");
        transaction.requireActive();
        if (!scanLegacyChunks().isEmpty()) {
            throw new StorageException("DuckDB V2 export does not support legacy fixture rows");
        }
        if (!pendingAppends.isEmpty() || !pendingDeletes.isEmpty() || !pendingUpdates.isEmpty()) {
            throw new StorageException("DuckDB V2 export requires no uncommitted table writes");
        }
        long snapshot = transaction.snapshot().catalogVersion();
        var rows = new ArrayList<List<Object>>();
        for (var row : committedRows) {
            if (row.commitVersion <= snapshot && (row.deleteVersion == Long.MAX_VALUE || row.deleteVersion > snapshot)) {
                rows.add(java.util.Collections.unmodifiableList(new ArrayList<>(row.valuesAt(snapshot))));
            }
        }
        return List.copyOf(rows);
    }

    /** Stable IDs in the same order as transactional row chunks; legacy fixture rows are intentionally excluded. */
    public synchronized List<Long> visibleRowIds(dev.trentdb.transaction.Transaction transaction) {
        if (!scanLegacyChunks().isEmpty()) throw new StorageException("Predicate DELETE requires transaction-managed rows with stable logical row IDs");
        long snapshot = transaction.snapshot().catalogVersion(); var ids = new ArrayList<Long>();
        for (var row : committedRows) if (row.commitVersion <= snapshot && (row.deleteVersion == Long.MAX_VALUE || row.deleteVersion > snapshot) && !pendingDeletes.getOrDefault(transaction, java.util.Set.of()).contains(row.rowId)) ids.add(row.rowId);
        var pending = pendingAppends.get(transaction);
        if (pending != null) for (var row : pending.rows) if (!pendingDeletes.getOrDefault(transaction, java.util.Set.of()).contains(row.rowId)) ids.add(row.rowId);
        return List.copyOf(ids);
    }
    private PendingRow pendingRowById(dev.trentdb.transaction.Transaction transaction, long rowId) { var pending = pendingAppends.get(transaction); if (pending != null) for (var row : pending.rows) if (row.rowId == rowId) return row; return null; }

    private PendingUpdate pendingUpdateById(dev.trentdb.transaction.Transaction transaction, long rowId) { var updates = pendingUpdates.get(transaction); return updates == null ? null : updates.rows.get(rowId); }
    private VersionedRow rowById(long rowId) { for (var row : committedRows) if (row.rowId == rowId) return row; return null; }
    public record RowReplacement(long rowId, List<Object> values) { public RowReplacement { if (rowId <= 0) throw new IllegalArgumentException("Row ID must be positive"); values = java.util.Collections.unmodifiableList(new ArrayList<>(values)); } }
    private static final class PendingRows { private final List<PendingRow> rows = new ArrayList<>(); private boolean committed; }
    private static final class PendingUpdates { private final java.util.TreeMap<Long, PendingUpdate> rows = new java.util.TreeMap<>(); private boolean committed; }
    private record PendingRow(long rowId, List<Object> values) { }
    private record PendingUpdate(long rowId, List<Object> values) { private PendingUpdate(RowReplacement replacement) { this(replacement.rowId(), replacement.values()); } }
    private record RowValueVersion(List<Object> values, long commitVersion, PendingUpdates owner) { }
    private static final class VersionedRow { private final long rowId; private final List<RowValueVersion> values = new ArrayList<>(); private final long commitVersion; private final PendingRows owner; private long deleteVersion = Long.MAX_VALUE; private VersionedRow(long rowId, List<Object> initialValues, long commitVersion, PendingRows owner) { this.rowId = rowId; this.commitVersion = commitVersion; this.owner = owner; this.values.add(new RowValueVersion(initialValues, commitVersion, null)); } private List<Object> valuesAt(long snapshot) { for (int index = values.size() - 1; index >= 0; index--) { var version = values.get(index); if (version.commitVersion <= snapshot) return version.values; } throw new StorageException("No visible row value version for row " + rowId); } }

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
