package dev.trentdb.common;

/** Shared DuckDB-standard execution vector size. */
public final class VectorSize {
    /** DuckDB V2.0 {@code DEFAULT_STANDARD_VECTOR_SIZE}; stored in database headers. */
    public static final int STANDARD_VECTOR_SIZE = 2048;

    private VectorSize() {
    }
}
