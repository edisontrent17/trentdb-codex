package dev.trentdb.transaction;

import java.util.ArrayList;
import java.util.List;

public final class Transaction {
    private final long id;
    private final TransactionManager owner;
    private final TransactionSnapshot snapshot;
    private final List<TransactionParticipant> participants = new ArrayList<>();
    private TransactionState state = TransactionState.ACTIVE;

    Transaction(long id, long catalogVersion, TransactionManager owner) {
        this.id = id;
        this.owner = owner;
        this.snapshot = new TransactionSnapshot(id, catalogVersion);
    }

    public long id() {
        return id;
    }

    public TransactionSnapshot snapshot() {
        return snapshot;
    }

    public synchronized TransactionState state() {
        return state;
    }

    /** Enlists a write-aware subsystem exactly once. */
    public synchronized void enlist(TransactionParticipant participant) {
        requireActive();
        if (!participants.contains(participant)) {
            participants.add(participant);
        }
    }

    synchronized List<TransactionParticipant> participants() {
        return List.copyOf(participants);
    }

    synchronized void markCommitted() {
        requireActive();
        state = TransactionState.COMMITTED;
    }

    synchronized void markRolledBack() {
        if (state == TransactionState.COMMITTED) {
            throw new IllegalStateException("Committed transaction cannot be rolled back");
        }
        state = TransactionState.ROLLED_BACK;
    }

    boolean isOwnedBy(TransactionManager transactionManager) {
        return owner == transactionManager;
    }

    public synchronized void requireActive() {
        if (state != TransactionState.ACTIVE) {
            throw new IllegalStateException("Transaction " + id + " is " + state);
        }
    }
}
