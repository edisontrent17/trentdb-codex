package dev.trentdb.execution;

import dev.trentdb.ast.*;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.ddl.TransactionalDdlExecutor;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.transaction.TransactionState;

import java.util.List;
import java.util.Objects;

/** Connection-scoped SQL execution boundary with explicit transaction state and autocommit. */
public final class DatabaseSession {
    private static final QueryResult EMPTY_RESULT = new QueryResult(List.of(), List.of());
    private final Catalog catalog;
    private final StorageManager storageManager;
    private final TransactionManager transactionManager;
    private final SqlParser parser = new SqlParser();
    private final LogicalPlanner logicalPlanner = new LogicalPlanner();
    private Transaction activeTransaction;

    public DatabaseSession(Catalog catalog, StorageManager storageManager, TransactionManager transactionManager) {
        this.catalog = Objects.requireNonNull(catalog); this.storageManager = Objects.requireNonNull(storageManager); this.transactionManager = Objects.requireNonNull(transactionManager);
    }

    public synchronized QueryResult execute(String sql) { return execute(parser.parse(sql)); }

    public synchronized QueryResult execute(Statement statement) {
        Objects.requireNonNull(statement);
        if (statement instanceof BeginTransactionStatement) return begin();
        if (statement instanceof CommitStatement) return commit();
        if (statement instanceof RollbackStatement) return rollback();
        boolean write = isWrite(statement); boolean explicit = activeTransaction != null; Transaction transaction = explicit ? activeTransaction : (write ? transactionManager.startTransaction() : transactionManager.startReadTransaction());
        try {
            var bound = new Binder(catalog).bind(transaction, statement); var logical = logicalPlanner.plan(bound);
            var result = new QueryExecutor(storageManager, new TransactionalDdlExecutor(catalog, storageManager, transactionManager, transaction)).execute(logical);
            if (!explicit) { if (write) transactionManager.commit(transaction); else transactionManager.rollback(transaction); }
            return result;
        } catch (RuntimeException failure) {
            if (!explicit && transaction.state() == TransactionState.ACTIVE) { try { transactionManager.rollback(transaction); } catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); } }
            if (explicit && transaction.state() != TransactionState.ACTIVE) activeTransaction = null;
            throw failure;
        }
    }

    public synchronized boolean inTransaction() { return activeTransaction != null && activeTransaction.state() == TransactionState.ACTIVE; }
    public synchronized Transaction activeTransaction() { return activeTransaction; }

    private QueryResult begin() {
        if (inTransaction()) throw new SessionException("Cannot start a transaction within a transaction");
        activeTransaction = transactionManager.startReadTransaction(); return EMPTY_RESULT;
    }

    private QueryResult commit() {
        var transaction = requireActive("COMMIT");
        try { transactionManager.commit(transaction); return EMPTY_RESULT; } finally { if (transaction.state() != TransactionState.ACTIVE) activeTransaction = null; }
    }

    private QueryResult rollback() {
        var transaction = requireActive("ROLLBACK");
        try { transactionManager.rollback(transaction); return EMPTY_RESULT; } finally { if (transaction.state() != TransactionState.ACTIVE) activeTransaction = null; }
    }

    private Transaction requireActive(String command) {
        if (!inTransaction()) throw new SessionException(command + " requires an active transaction"); return activeTransaction;
    }

    private static boolean isWrite(Statement statement) {
        return statement instanceof CreateTableStatement || statement instanceof DropTableStatement || statement instanceof CreateIndexStatement || statement instanceof DropIndexStatement || statement instanceof InsertStatement || statement instanceof UpdateStatement || statement instanceof DeleteStatement;
    }
}
