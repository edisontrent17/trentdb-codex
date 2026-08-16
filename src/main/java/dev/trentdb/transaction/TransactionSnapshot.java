package dev.trentdb.transaction;

/**
 * A stable read view. The catalog version is the newest committed version a
 * transaction may observe; uncommitted changes are visible only to their own
 * transaction.
 */
public record TransactionSnapshot(long transactionId, long catalogVersion) {
    public TransactionSnapshot(long transactionId) {
        this(transactionId, 0);
    }
}
