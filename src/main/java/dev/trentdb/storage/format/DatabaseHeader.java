package dev.trentdb.storage.format;

/** One of the two checkpoint headers in a DuckDB V2.0 single-file database. */
public record DatabaseHeader(long iteration, long metaBlock, long freeList, long blockCount,
                             long blockAllocationSize, long vectorSize, long storageCompatibility) {
    public DatabaseHeader {
        if (blockCount < 0) {
            throw new IllegalArgumentException("blockCount must not be negative");
        }
    }

    public static DatabaseHeader emptyV2() {
        return new DatabaseHeader(0, StorageFormat.INVALID_BLOCK, StorageFormat.INVALID_BLOCK, 0,
                StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE, StorageFormat.STANDARD_VECTOR_SIZE,
                StorageFormat.STORAGE_VERSION);
    }
}
