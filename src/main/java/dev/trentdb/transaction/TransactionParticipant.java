package dev.trentdb.transaction;

/**
 * A transactional subsystem enlisted by a write transaction.
 *
 * <p>The transaction manager serializes prepare/commit while holding its
 * commit boundary. Participants must not publish changes during prepare.</p>
 */
public interface TransactionParticipant {
    void prepareCommit(Transaction transaction, long commitVersion);

    void commit(Transaction transaction, long commitVersion);

    void rollback(Transaction transaction);
    /**
     * Releases commit-time bookkeeping after every participant has published
     * successfully. Implementations must not make visibility changes here and
     * must not throw: a transaction is already committed when this hook runs.
     */
    default void completeCommit(Transaction transaction, long commitVersion) {
    }
}
