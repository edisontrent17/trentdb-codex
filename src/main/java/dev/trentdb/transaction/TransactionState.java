package dev.trentdb.transaction;

/** The lifecycle of a transaction. */
public enum TransactionState {
    ACTIVE,
    COMMITTED,
    ROLLED_BACK
}
