package dev.trentdb.storage.format;

/** Exact V2.0 metadata sub-block layout used inside an ordinary DuckDB storage block. */
public final class MetadataBlockLayout {
    public static final int METADATA_BLOCK_COUNT = 64;
    public static final int NEXT_BLOCK_POINTER_SIZE = Long.BYTES;
    private static final int ALIGNMENT = Long.BYTES;

    private MetadataBlockLayout() {
    }

    /**
     * Matches {@code AlignValueFloor(block_manager.GetBlockSize() / 64)} in DuckDB.
     * The supplied size is the usable block payload, after its checksum header.
     */
    public static int metadataBlockSize(int usableBlockSize) {
        if (usableBlockSize <= 0) {
            throw new StorageFormatException("DuckDB usable block size must be positive: " + usableBlockSize);
        }
        int candidate = usableBlockSize / METADATA_BLOCK_COUNT;
        int result = candidate / ALIGNMENT * ALIGNMENT;
        if (result < NEXT_BLOCK_POINTER_SIZE) {
            throw new StorageFormatException("DuckDB block is too small for metadata sub-blocks: " + usableBlockSize);
        }
        return result;
    }

    public static int blockOffset(MetaBlockPointer pointer, int usableBlockSize) {
        if (!pointer.isValid()) {
            throw new StorageFormatException("Cannot address an invalid DuckDB metadata pointer");
        }
        int metadataBlockSize = metadataBlockSize(usableBlockSize);
        if (pointer.blockIndex() >= METADATA_BLOCK_COUNT || pointer.offset() > metadataBlockSize) {
            throw new StorageFormatException("DuckDB metadata pointer is outside its sub-block: " + pointer);
        }
        return Math.addExact(Math.multiplyExact(pointer.blockIndex(), metadataBlockSize), pointer.offset());
    }
}
