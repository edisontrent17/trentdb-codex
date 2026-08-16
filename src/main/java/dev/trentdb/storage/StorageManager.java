package dev.trentdb.storage;

import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionParticipant;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Table-storage registry and transactional participant. Storage instances are
 * keyed by immutable catalog-entry identity so readers holding an older table
 * version retain a safe reference after DROP. Reclamation is intentionally a
 * later checkpoint.
 */
public final class StorageManager implements TransactionParticipant {
    private final Map<TableCatalogEntry, InMemoryTableStorage> tables = new IdentityHashMap<>();
    private final Map<TableCatalogEntry, Long> retiredAt = new IdentityHashMap<>();
    private final Map<Transaction, StorageTransactionState> pendingTransactions = new IdentityHashMap<>();

    /** Legacy non-transactional fixture helper. Production DDL uses stageCreate. */
    public synchronized InMemoryTableStorage createTable(TableCatalogEntry table) {
        var storage = new InMemoryTableStorage(table);
        var previous = tables.putIfAbsent(table, storage);
        if (previous != null) throw new StorageException("Storage already exists for table: " + table.name());
        return storage;
    }

    /** Creates storage private to a transaction; catalog visibility remains the gate. */
    public synchronized InMemoryTableStorage stageCreate(Transaction transaction, TableCatalogEntry table) {
        requireActive(transaction);
        var state = stateForWrite(transaction);
        if (tables.containsKey(table)) throw new StorageException("Storage already exists for table: " + table.name());
        var storage = new InMemoryTableStorage(table);
        tables.put(table, storage);
        state.created.put(table, storage);
        return storage;
    }

    /** Stages a row append for a table visible to this transaction. */
    public synchronized long stageAppend(Transaction transaction, TableCatalogEntry table, java.util.List<Object> values, long rowId) {
        requireActive(transaction);
        var storage = getTable(table);
        long staged = storage.stageAppend(transaction, values, rowId);
        stateForWrite(transaction).appended.add(storage);
        return staged;
    }

    /** Recovery-only explicit-ID append. Normal inserts retain strict sequential allocation. */
    public synchronized long stageRecoveredAppend(Transaction transaction, TableCatalogEntry table, java.util.List<Object> values, long rowId) {
        requireActive(transaction);
        var storage = getTable(table);
        long staged = storage.stageRecoveredAppend(transaction, values, rowId);
        stateForWrite(transaction).appended.add(storage);
        return staged;
    }

    /** Stages all validated INSERT rows as one statement batch and returns ordered stable IDs. */
    public synchronized java.util.List<Long> stageAppendBatch(Transaction transaction, TableCatalogEntry table, java.util.List<java.util.List<Object>> rows) {
        requireActive(transaction); var storage = getTable(table); var ids = storage.stageAppendBatch(transaction, rows); stateForWrite(transaction).appended.add(storage); return ids;
    }
    public synchronized void discardAppendBatch(Transaction transaction, TableCatalogEntry table, java.util.List<Long> ids) {
        requireActive(transaction); getTable(table).discardAppendBatch(transaction, ids);
    }

    public synchronized long stageAppend(Transaction transaction, TableCatalogEntry table, java.util.List<Object> values) {
        requireActive(transaction); var storage = getTable(table); long rowId = storage.stageAppend(transaction, values); stateForWrite(transaction).appended.add(storage); return rowId;
    }


    /** Stages tombstones for every row visible to this transaction. */
    public synchronized java.util.List<Long> stageDeleteAll(Transaction transaction, TableCatalogEntry table) {
        requireActive(transaction); var storage = getTable(table); var ids = storage.stageDeleteAll(transaction); stateForWrite(transaction).deleted.add(storage); return ids;
    }
    public synchronized void stageDelete(Transaction transaction, TableCatalogEntry table, java.util.List<Long> rowIds) {
        requireActive(transaction); var storage = getTable(table); storage.stageDelete(transaction, rowIds); stateForWrite(transaction).deleted.add(storage);
    }
    /** Stages full-row MVCC replacement values under stable logical row IDs. */
    public synchronized void stageUpdates(Transaction transaction, TableCatalogEntry table, java.util.List<InMemoryTableStorage.RowReplacement> replacements) {
        requireActive(transaction); var storage = getTable(table); storage.stageUpdates(transaction, replacements); stateForWrite(transaction).updated.add(storage);
    }

    public synchronized void stageDrop(Transaction transaction, TableCatalogEntry table) {
        requireActive(transaction); if (!tables.containsKey(table)) throw new StorageException("Storage not found for table: " + table.name()); stateForWrite(transaction).dropped.add(table);
    }

    public synchronized java.util.List<Long> visibleRowIds(Transaction transaction, TableCatalogEntry table) { return getTable(table).visibleRowIds(transaction); }

    public synchronized InMemoryTableStorage getTable(TableCatalogEntry table) {
        var storage = tables.get(table);
        if (storage == null) throw new StorageException("Storage not found for table: " + table.name());
        return storage;
    }

    /**
     * Reads only committed transaction-managed rows at the supplied snapshot for V2 export.
     * Legacy fixture vectors and every outstanding storage write are deliberately rejected.
     */
    public synchronized java.util.List<java.util.List<Object>> exportCommittedRows(
            Transaction transaction, TableCatalogEntry table
    ) {
        requireActive(transaction);
        if (!pendingTransactions.isEmpty()) {
            throw new StorageException("DuckDB V2 export requires no uncommitted storage writes");
        }
        return getTable(table).exportCommittedRows(transaction);
    }

    /** Recovery-only reservation that preserves monotonic row IDs across ignored WAL transactions. */
    public synchronized void reserveNextRowIdAtLeast(TableCatalogEntry table, long nextRowId) {
        if (nextRowId <= 0) throw new IllegalArgumentException("Next row ID must be positive");
        getTable(table).reserveNextRowIdAtLeast(nextRowId);
    }


    /** True after committed DROP; retained storage is not visible through a new catalog snapshot. */
    public synchronized boolean isRetired(TableCatalogEntry table) {
        return retiredAt.containsKey(table);
    }

    @Override public synchronized void prepareCommit(Transaction transaction, long commitVersion) {
        var state = pendingTransactions.get(transaction);
        if (state == null) return;

        for (var created : state.created.entrySet()) {
            if (tables.get(created.getKey()) != created.getValue()) throw new StorageException("Staged storage changed concurrently");
        }
        for (var dropped : state.dropped) {
            if (!tables.containsKey(dropped)) throw new StorageException("Storage changed concurrently: " + dropped.name());
        }
        state.preparedVersion = commitVersion;
    }

    @Override public synchronized void commit(Transaction transaction, long commitVersion) {
        var state = pendingTransactions.get(transaction);
        if (state == null) return;
        if (state.preparedVersion == null || state.preparedVersion != commitVersion)
            throw new IllegalStateException("Storage transaction was not prepared for commit");
        for (var dropped : state.dropped) {
            if (state.created.containsKey(dropped)) {
                tables.remove(dropped);
            } else {
                state.previousRetirements.put(dropped, retiredAt.put(dropped, commitVersion));
            }
        }
        for (var storage : state.appended) storage.commitAppends(transaction, commitVersion);
        for (var storage : state.updated) storage.commitUpdates(transaction, commitVersion);
        for (var storage : state.deleted) storage.commitDeletes(transaction, commitVersion);
        state.published = true;
    }

    @Override public synchronized void completeCommit(Transaction transaction, long commitVersion) {
        var state = pendingTransactions.get(transaction);
        if (state != null && state.published && state.preparedVersion != null && state.preparedVersion == commitVersion) {
            for (var storage : state.appended) storage.completeAppends(transaction);
            for (var storage : state.updated) storage.completeUpdates(transaction);
            for (var storage : state.deleted) storage.completeDeletes(transaction);
            pendingTransactions.remove(transaction);
        }
    }

    @Override public synchronized void rollback(Transaction transaction) {
        var state = pendingTransactions.remove(transaction);
        if (state == null) return;
        for (var storage : state.appended) storage.rollbackAppends(transaction);
        for (var storage : state.updated) storage.rollbackUpdates(transaction);
        for (var storage : state.deleted) storage.rollbackDeletes(transaction);
        for (var created : state.created.entrySet()) {
            if (tables.get(created.getKey()) == created.getValue()) tables.remove(created.getKey());
        }
        if (state.published) {
            for (var dropped : state.dropped) {
                if (state.created.containsKey(dropped)) continue;
                Long previous = state.previousRetirements.get(dropped);
                if (previous == null) retiredAt.remove(dropped); else retiredAt.put(dropped, previous);
            }
        }
    }

    private StorageTransactionState stateForWrite(Transaction transaction) {
        var state = pendingTransactions.get(transaction);
        if (state == null) {
            state = new StorageTransactionState();
            pendingTransactions.put(transaction, state);
            transaction.enlist(this);
        }
        return state;
    }

    private static void requireActive(Transaction transaction) {
        if (transaction == null) throw new IllegalArgumentException("Storage operation requires a transaction");
        transaction.requireActive();
    }

    private static final class StorageTransactionState {
        private final Map<TableCatalogEntry, InMemoryTableStorage> created = new IdentityHashMap<>();
        private final Set<TableCatalogEntry> dropped = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<InMemoryTableStorage> appended = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<InMemoryTableStorage> deleted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<InMemoryTableStorage> updated = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<TableCatalogEntry, Long> previousRetirements = new IdentityHashMap<>();
        private Long preparedVersion;
        private boolean published;
    }
}
