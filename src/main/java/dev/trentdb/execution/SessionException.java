package dev.trentdb.execution;

/** Deterministic connection/session transaction-control failure. */
public final class SessionException extends RuntimeException {
    public SessionException(String message) { super(message); }
}
