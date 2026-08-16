package dev.trentdb.execution.ddl;

import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.CreateIndexStatement;
import dev.trentdb.ast.DropIndexStatement;
import dev.trentdb.ast.DropTableStatement;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;

/** Stages catalog and table storage together, then records a deterministic WAL intent. */
public final class TransactionalDdlExecutor {
    private final Catalog catalog;
    private final StorageManager storageManager;
    private final TransactionManager transactionManager;
    private final Transaction transaction;

    public TransactionalDdlExecutor(
            Catalog catalog, StorageManager storageManager, TransactionManager transactionManager, Transaction transaction
    ) {
        this.catalog = catalog;
        this.storageManager = storageManager;
        this.transactionManager = transactionManager;
        this.transaction = transaction;
    }

    public Transaction transaction() { return transaction; }

    public TableCatalogEntry createTable(CreateTableStatement statement) {
        var table = catalog.createTable(transaction, statement);
        try {
            storageManager.stageCreate(transaction, table);
            journal(DdlWalPayload.createTable(statement));
            return table;
        } catch (RuntimeException failure) {
            abortAfterStaging(failure);
            throw failure;
        }
    }

    public void createIndex(CreateIndexStatement statement) {
        try {
            catalog.createIndex(transaction, statement);
            journal(DdlWalPayload.createIndex(statement));
        } catch (RuntimeException failure) {
            abortAfterStaging(failure);
            throw failure;
        }
    }

    public void dropIndex(DropIndexStatement statement) {
        try {
            catalog.dropIndex(transaction, statement.name());
            journal(DdlWalPayload.dropIndex(statement));
        } catch (RuntimeException failure) {
            abortAfterStaging(failure);
            throw failure;
        }
    }

    public void insert(dev.trentdb.planner.BoundInsertStatement statement) {
        var rows = materializeInsertRows(statement); java.util.List<Long> rowIds = java.util.List.of(); int journaled = 0;
        try {
            rowIds = storageManager.stageAppendBatch(transaction, statement.table(), rows);
            for (int index = 0; index < rows.size(); index++) { var oneRow = new dev.trentdb.planner.BoundInsertStatement(statement.table(), statement.targetOrdinals(), statement.rows().get(index)); journal(DdlWalPayload.insert(oneRow, rowIds.get(index))); journaled++; }
        } catch (RuntimeException failure) {
            if (!rowIds.isEmpty()) try { storageManager.discardAppendBatch(transaction, statement.table(), rowIds); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (journaled > 0) abortAfterStaging(failure);
            throw failure;
        }
    }

    private java.util.List<java.util.List<Object>> materializeInsertRows(dev.trentdb.planner.BoundInsertStatement statement) {
        var rows = new java.util.ArrayList<java.util.List<Object>>(statement.rows().size());
        for (var values : statement.rows()) { var row = new java.util.ArrayList<Object>(java.util.Collections.nCopies(statement.table().columns().size(), null)); for (int index = 0; index < statement.targetOrdinals().size(); index++) { int ordinal = statement.targetOrdinals().get(index); row.set(ordinal, coerce(values.get(index).value(), statement.table().columns().get(ordinal).logicalType())); } rows.add(java.util.Collections.unmodifiableList(row)); }
        return java.util.List.copyOf(rows);
    }

    private Object coerce(Object value, dev.trentdb.types.LogicalType target) {
        if (value == null) return null;
        if (target.equals(dev.trentdb.types.LogicalType.BIGINT)) return ((Number) value).longValue();
        if (target.equals(dev.trentdb.types.LogicalType.DOUBLE)) return ((Number) value).doubleValue();
        if (target.equals(dev.trentdb.types.LogicalType.INTEGER)) return ((Number) value).intValue();
        return value;
    }


    public void delete(dev.trentdb.planner.BoundDeleteStatement statement) {
        try { var rowIds = statement.predicate() == null ? storageManager.stageDeleteAll(transaction, statement.table()) : matchingRowIds(statement); journal(DdlWalPayload.delete(statement, rowIds)); }
        catch (RuntimeException failure) { abortAfterStaging(failure); throw failure; }
    }


    private java.util.List<Long> matchingRowIds(dev.trentdb.planner.BoundDeleteStatement statement) {
        var chunks = storageManager.getTable(statement.table()).scanChunks(transaction); var ids = storageManager.getTable(statement.table()).visibleRowIds(transaction);
        var matches = new java.util.ArrayList<Long>(); int offset = 0; var evaluator = new dev.trentdb.execution.ExpressionExecutor(storageManager);
        for (var chunk : chunks) { var result = evaluator.execute(statement.predicate(), chunk); for (int row = 0; row < chunk.cardinality(); row++, offset++) if (!result.isNull(row) && result.getBoolean(row)) matches.add(ids.get(offset)); }
        matches.sort(Long::compare); storageManager.stageDelete(transaction, statement.table(), matches); return java.util.List.copyOf(matches);
    }


    /** Materializes UPDATE replacements once against this writer snapshot, then journals stable-ID full rows. */
    public void update(dev.trentdb.planner.BoundUpdateStatement statement) {
        try { var replacements = matchingUpdateRows(statement); storageManager.stageUpdates(transaction, statement.table(), replacements); journal(DdlWalPayload.update(statement, replacements)); }
        catch (RuntimeException failure) { abortAfterStaging(failure); throw failure; }
    }

    private java.util.List<dev.trentdb.storage.InMemoryTableStorage.RowReplacement> matchingUpdateRows(dev.trentdb.planner.BoundUpdateStatement statement) {
        var table = storageManager.getTable(statement.table()); var ids = table.visibleRowIds(transaction); var chunks = table.scanChunks(transaction);
        var replacements = new java.util.ArrayList<dev.trentdb.storage.InMemoryTableStorage.RowReplacement>(); int offset = 0; var evaluator = new dev.trentdb.execution.ExpressionExecutor(storageManager); var targetType = statement.table().columns().get(statement.targetOrdinal()).logicalType();
        for (var chunk : chunks) { var predicate = statement.predicate() == null ? null : evaluator.execute(statement.predicate(), chunk); var values = evaluator.execute(statement.value(), chunk); for (int row = 0; row < chunk.cardinality(); row++, offset++) { if (predicate != null && (predicate.isNull(row) || !predicate.getBoolean(row))) continue; var replacement = new java.util.ArrayList<Object>(chunk.vectors().size()); for (var column : chunk.vectors()) replacement.add(column.boxedValue(row)); replacement.set(statement.targetOrdinal(), coerce(values.boxedValue(row), targetType)); replacements.add(new dev.trentdb.storage.InMemoryTableStorage.RowReplacement(ids.get(offset), replacement)); } }
        replacements.sort(java.util.Comparator.comparingLong(dev.trentdb.storage.InMemoryTableStorage.RowReplacement::rowId)); return java.util.List.copyOf(replacements);
    }


    public void dropTable(DropTableStatement statement) {
        var table = catalog.lookupTable(transaction, statement.name());
        catalog.dropTable(transaction, statement.name());
        try {
            storageManager.stageDrop(transaction, table);
            journal(DdlWalPayload.dropTable(statement));
        } catch (RuntimeException failure) {
            abortAfterStaging(failure);
            throw failure;
        }
    }

    private void journal(byte[] payload) {
        transactionManager.recordWrite(transaction, payload);
    }

    private void abortAfterStaging(RuntimeException primaryFailure) {
        try {
            transactionManager.rollback(transaction);
        } catch (RuntimeException rollbackFailure) {
            primaryFailure.addSuppressed(rollbackFailure);
        }
    }
}
