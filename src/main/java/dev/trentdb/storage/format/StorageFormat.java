package dev.trentdb.storage.format;

import dev.trentdb.common.VectorSize;

/** Constants for the DuckDB V2.0 (storage format 69) single-file layout. */
public final class StorageFormat {
    public static final int SECTOR_SIZE = 4096;
    public static final int FILE_HEADER_SIZE = 4096;
    public static final int HEADER_CHECKSUM_SIZE = Long.BYTES;
    public static final int MAIN_HEADER_MAGIC_OFFSET = HEADER_CHECKSUM_SIZE;
    public static final int FILE_HEADER_COUNT = 3;
    public static final int MINIMUM_DATABASE_FILE_SIZE = FILE_HEADER_COUNT * FILE_HEADER_SIZE;
    public static final long BLOCK_START = (long) FILE_HEADER_COUNT * FILE_HEADER_SIZE;

    public static final int DEFAULT_BLOCK_ALLOCATION_SIZE = 262_144;
    public static final int MINIMUM_BLOCK_ALLOCATION_SIZE = 16_384;
    public static final int MAXIMUM_BLOCK_ALLOCATION_SIZE = 262_144;
    public static final int DEFAULT_BLOCK_HEADER_SIZE = Long.BYTES;
    public static final int MAXIMUM_BLOCK_HEADER_SIZE = 128;
    public static final long INVALID_BLOCK = -1L;
    public static final int DEFAULT_BLOCK_SIZE = DEFAULT_BLOCK_ALLOCATION_SIZE - DEFAULT_BLOCK_HEADER_SIZE;

    public static final int STORAGE_VERSION = 69;
    public static final int DEPRECATED_MAIN_HEADER_VERSION = 999;
    public static final int STANDARD_VECTOR_SIZE = VectorSize.STANDARD_VECTOR_SIZE;
    public static final byte[] MAGIC_BYTES = {'D', 'U', 'C', 'K'};
    public static final int MAIN_HEADER_SERIALIZED_SIZE = 168;
    public static final int DATABASE_HEADER_SERIALIZED_SIZE = 56;

    private StorageFormat() {
    }

    public static void validateBlockAllocationSize(long blockAllocationSize) {
        if (blockAllocationSize < MINIMUM_BLOCK_ALLOCATION_SIZE
                || blockAllocationSize > MAXIMUM_BLOCK_ALLOCATION_SIZE
                || (blockAllocationSize & (blockAllocationSize - 1)) != 0) {
            throw new StorageFormatException("Invalid DuckDB block allocation size: " + blockAllocationSize);
        }
    }

    public static void validateBlockHeaderSize(long blockHeaderSize) {
        if (blockHeaderSize < DEFAULT_BLOCK_HEADER_SIZE
                || blockHeaderSize > MAXIMUM_BLOCK_HEADER_SIZE
                || (blockHeaderSize & 7) != 0) {
            throw new StorageFormatException("Invalid DuckDB block header size: " + blockHeaderSize);
        }
    }
}
