package dev.trentdb.execution.ddl;

/** INSERT is currently executed by TransactionalDdlExecutor to share one write boundary. */
final class TransactionalDmlExecutor {
    private TransactionalDmlExecutor() { }
}
