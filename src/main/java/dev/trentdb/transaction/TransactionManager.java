package dev.trentdb.transaction;

import java.util.concurrent.atomic.AtomicLong;

public final class TransactionManager {
    private final AtomicLong nextTransactionId = new AtomicLong(1);

    public Transaction startTransaction() {
        return new Transaction(nextTransactionId.getAndIncrement());
    }
}
