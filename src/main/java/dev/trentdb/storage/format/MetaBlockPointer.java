package dev.trentdb.storage.format;

/**
 * DuckDB's packed pointer to one of the 64 metadata sub-blocks in a storage block.
 *
 * <p>The low 56 bits name the storage block, the high byte names the metadata sub-block,
 * and {@link #offset()} is relative to that sub-block. This is the on-disk representation
 * used by {@code duckdb/storage/metadata/metadata_manager.cpp}.</p>
 */
public record MetaBlockPointer(long blockPointer, int offset) {
    public static final long INVALID_BLOCK_POINTER = -1L;
    public static final int BLOCK_ID_BITS = 56;
    public static final long BLOCK_ID_MASK = (1L << BLOCK_ID_BITS) - 1;

    public MetaBlockPointer {
        if (offset < 0) {
            throw new StorageFormatException("DuckDB metadata offset must not be negative: " + offset);
        }
        if (blockPointer != INVALID_BLOCK_POINTER && (blockPointer >>> BLOCK_ID_BITS) >= MetadataBlockLayout.METADATA_BLOCK_COUNT) {
            throw new StorageFormatException("DuckDB metadata block index exceeds "
                    + MetadataBlockLayout.METADATA_BLOCK_COUNT + ": " + (blockPointer >>> BLOCK_ID_BITS));
        }
    }

    public static MetaBlockPointer invalid() {
        return new MetaBlockPointer(INVALID_BLOCK_POINTER, 0);
    }

    public static MetaBlockPointer of(long blockId, int blockIndex, int offset) {
        if (blockId < 0 || (blockId & ~BLOCK_ID_MASK) != 0) {
            throw new StorageFormatException("DuckDB metadata block id is outside its 56-bit encoding: " + blockId);
        }
        if (blockIndex < 0 || blockIndex >= MetadataBlockLayout.METADATA_BLOCK_COUNT) {
            throw new StorageFormatException("DuckDB metadata block index is outside [0, "
                    + MetadataBlockLayout.METADATA_BLOCK_COUNT + "): " + blockIndex);
        }
        return new MetaBlockPointer(blockId | ((long) blockIndex << BLOCK_ID_BITS), offset);
    }

    public boolean isValid() {
        return blockPointer != INVALID_BLOCK_POINTER;
    }

    public long blockId() {
        requireValid();
        return blockPointer & BLOCK_ID_MASK;
    }

    public int blockIndex() {
        requireValid();
        return (int) (blockPointer >>> BLOCK_ID_BITS);
    }

    private void requireValid() {
        if (!isValid()) {
            throw new StorageFormatException("DuckDB metadata pointer is invalid");
        }
    }
}
