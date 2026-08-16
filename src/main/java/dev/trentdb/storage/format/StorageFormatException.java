package dev.trentdb.storage.format;

import dev.trentdb.storage.StorageException;

/** Thrown when a file cannot be interpreted as the supported DuckDB V2.0 format. */
public final class StorageFormatException extends StorageException {
    public StorageFormatException(String message) {
        super(message);
    }
}
