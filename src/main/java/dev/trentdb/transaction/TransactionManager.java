package dev.trentdb.transaction;

import dev.trentdb.storage.wal.WriteAheadLog;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Coordinates serialized catalog visibility with an optional durable WAL. */
public final class TransactionManager {
    private final AtomicLong nextTransactionId = new AtomicLong(1);
    private final WriteAheadLog wal;
    private long committedCatalogVersion;
    private final Set<Transaction> walStarted = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Creates an in-memory coordinator without a WAL, suitable only for transient use. */
    public TransactionManager() {
        this(null);
    }

    /**
     * Creates a coordinator backed by the supplied internal WAL. The WAL must
     * remain open for the lifetime of this manager.
     */
    public TransactionManager(WriteAheadLog wal) {
        this.wal = wal;
    }
    /**
     * Restores a coordinator after WAL recovery. The supplied counters must be
     * derived from a fully validated WAL before this manager is exposed.
     */
    public TransactionManager(WriteAheadLog wal, long nextTransactionId, long committedCatalogVersion) {
        if (nextTransactionId <= 0) {
            throw new IllegalArgumentException("Next transaction ID must be positive");
        }
        if (committedCatalogVersion < 0) {
            throw new IllegalArgumentException("Committed catalog version must not be negative");
        }
        this.wal = wal;
        this.nextTransactionId.set(nextTransactionId);
        this.committedCatalogVersion = committedCatalogVersion;
    }


    public synchronized Transaction startTransaction() {
        var transaction = new Transaction(nextTransactionId.getAndIncrement(), committedCatalogVersion, this);
        if (wal != null) { wal.appendBegin(transaction.id()); walStarted.add(transaction); }
        return transaction;
    }

    /** Starts a snapshot transaction without WAL traffic; the first write activates WAL lazily. */
    public synchronized Transaction startReadTransaction() {
        return new Transaction(nextTransactionId.getAndIncrement(), committedCatalogVersion, this);
    }

    /** Appends an opaque write intent to the current transaction WAL stream. */
    public synchronized void recordWrite(Transaction transaction, byte[] payload) {
        requireTransaction(transaction);
        transaction.requireActive();
        if (wal == null) {
            throw new IllegalStateException("A WAL-backed TransactionManager is required to record writes");
        }
        if (walStarted.add(transaction)) wal.appendBegin(transaction.id());
        wal.appendWrite(transaction.id(), payload);
    }

    /**
     * Commits all enlisted write participants as one serialized visibility
     * boundary. A COMMIT record is appended and forced before any participant
     * publishes. If publication fails after that force, a forced compensating
     * ROLLBACK record prevents recovery from replaying the aborted transaction.
     */
    public synchronized void commit(Transaction transaction) {
        requireTransaction(transaction);
        transaction.requireActive();
        long commitVersion = committedCatalogVersion + 1;
        var participants = transaction.participants();
        if (!walStarted.contains(transaction) && participants.isEmpty()) { transaction.markCommitted(); return; }
        boolean commitRecordForced = false;
        try {
            for (var participant : participants) {
                participant.prepareCommit(transaction, commitVersion);
            }
            if (wal != null && walStarted.contains(transaction)) {
                wal.appendCommit(transaction.id(), commitVersion);
                wal.force();
                commitRecordForced = true;
            }
            for (var participant : participants) {
                participant.commit(transaction, commitVersion);
            }
            committedCatalogVersion = commitVersion;
            transaction.markCommitted();
        } catch (RuntimeException exception) {
            appendRollback(transaction, commitRecordForced, exception);
            var rollbackFailure = rollbackParticipants(transaction, participants);
            transaction.markRolledBack();
            if (rollbackFailure != null) {
                exception.addSuppressed(rollbackFailure);
            }
            walStarted.remove(transaction);
            throw exception;
        }
        completeParticipants(transaction, commitVersion, participants);
        walStarted.remove(transaction);
    }

    public synchronized void rollback(Transaction transaction) {
        requireTransaction(transaction);
        transaction.requireActive();
        RuntimeException failure = null;
        if (wal != null && walStarted.contains(transaction)) {
            try {
                wal.appendRollback(transaction.id());
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        var rollbackFailure = rollbackParticipants(transaction, transaction.participants());
        walStarted.remove(transaction);
        transaction.markRolledBack();
        if (failure != null && rollbackFailure != null) {
            failure.addSuppressed(rollbackFailure);
        }
        if (failure != null) {
            throw failure;
        }
        if (rollbackFailure != null) {
            throw rollbackFailure;
        }
    }

    public synchronized long committedCatalogVersion() {
        return committedCatalogVersion;
    }

    private void appendRollback(Transaction transaction, boolean force, RuntimeException primaryFailure) {
        if (wal == null || !walStarted.contains(transaction)) {
            return;
        }
        try {
            wal.appendRollback(transaction.id());
            if (force) {
                wal.force();
            }
        } catch (RuntimeException rollbackJournalFailure) {
            primaryFailure.addSuppressed(rollbackJournalFailure);
        }
    }

    private RuntimeException rollbackParticipants(
            Transaction transaction,
            java.util.List<TransactionParticipant> participants
    ) {
        RuntimeException failure = null;
        for (int index = participants.size() - 1; index >= 0; index--) {
            try {
                participants.get(index).rollback(transaction);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    private void completeParticipants(
            Transaction transaction,
            long commitVersion,
            java.util.List<TransactionParticipant> participants
    ) {
        for (var participant : participants) {
            participant.completeCommit(transaction, commitVersion);
        }
    }

    private void requireTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction must not be null");
        }
        if (!transaction.isOwnedBy(this)) {
            throw new IllegalArgumentException("Transaction belongs to a different transaction manager");
        }
    }
}
