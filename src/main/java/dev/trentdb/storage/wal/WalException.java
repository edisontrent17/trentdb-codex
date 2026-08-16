package dev.trentdb.storage.wal;

/** Raised when an internal WAL frame cannot be appended or validated. */
public final class WalException extends RuntimeException {
    public WalException(String message) { super(message); }
    public WalException(String message, Throwable cause) { super(message, cause); }
}
