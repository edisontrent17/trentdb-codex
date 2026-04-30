package dev.trentdb.transaction;

public final class Transaction {
    private final long id;
    private final TransactionSnapshot snapshot;

    Transaction(long id) {
        this.id = id;
        this.snapshot = new TransactionSnapshot(id);
    }

    public long id() {
        return id;
    }

    public TransactionSnapshot snapshot() {
        return snapshot;
    }
}
