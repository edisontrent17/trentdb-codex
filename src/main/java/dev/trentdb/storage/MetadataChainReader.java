package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.MetadataBlockLayout;
import dev.trentdb.storage.format.StorageFormatException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Raw reader for DuckDB's chained metadata sub-block envelope.
 *
 * <p>Each metadata sub-block begins with an eight-byte little-endian packed next pointer. The
 * remainder is opaque data; this class intentionally does not deserialize catalog, table, or
 * compression payloads. It rejects malformed pointers, truncated chains, cycles, and encrypted
 * files (the latter at {@link SingleFileBlockManager#openMetadataReadOnly(java.nio.file.Path)}).</p>
 */
public final class MetadataChainReader {
    private final SingleFileBlockManager blockManager;
    private final Set<Long> visitedPointers = new HashSet<>();
    private MetaBlockPointer nextPointer;
    private MetaBlockPointer currentSubBlock;
    private byte[] block;
    private int offset;
    private int capacity;

    public MetadataChainReader(SingleFileBlockManager blockManager, MetaBlockPointer firstPointer) {
        if (blockManager == null) {
            throw new IllegalArgumentException("blockManager must not be null");
        }
        if (firstPointer == null || !firstPointer.isValid()) {
            throw new StorageFormatException("Metadata chain must start at a valid DuckDB metadata pointer");
        }
        this.blockManager = blockManager;
        this.nextPointer = firstPointer;
    }

    public byte[] readFully(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Metadata read length must not be negative: " + length);
        }
        byte[] result = new byte[length];
        int destinationOffset = 0;
        while (destinationOffset < length) {
            ensureData();
            int copied = Math.min(length - destinationOffset, capacity - offset);
            System.arraycopy(block, offset, result, destinationOffset, copied);
            offset += copied;
            destinationOffset += copied;
        }
        return result;
    }

    public long readLong() {
        return ByteBuffer.wrap(readFully(Long.BYTES)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public int readInt() {
        return ByteBuffer.wrap(readFully(Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    public MetaBlockPointer currentPointer() {
        if (block == null) {
            throw new StorageFormatException("Metadata chain has not read a block yet");
        }
        return MetaBlockPointer.of(currentSubBlock.blockId(), currentSubBlock.blockIndex(),
                offset - MetadataBlockLayout.blockOffset(currentSubBlock, blockManager.usableBlockSize()));
    }

    private void ensureData() {
        if (block == null || offset == capacity) {
            readNextBlock();
        }
    }

    private void readNextBlock() {
        if (nextPointer == null || !nextPointer.isValid()) {
            throw new StorageFormatException("No more data remains in the DuckDB metadata chain");
        }
        if (nextPointer.blockId() >= blockManager.activeHeader().blockCount()) {
            throw new StorageFormatException("DuckDB metadata pointer references an uncommitted block: " + nextPointer);
        }
        if (!visitedPointers.add(nextPointer.blockPointer())) {
            throw new StorageFormatException("DuckDB metadata chain contains a cycle at " + nextPointer);
        }

        int usableBlockSize = blockManager.usableBlockSize();
        currentSubBlock = MetaBlockPointer.of(nextPointer.blockId(), nextPointer.blockIndex(), 0);
        int metadataBlockSize = MetadataBlockLayout.metadataBlockSize(usableBlockSize);
        int subBlockStart = MetadataBlockLayout.blockOffset(currentSubBlock, usableBlockSize);
        if (nextPointer.offset() > metadataBlockSize) {
            throw new StorageFormatException("DuckDB metadata pointer offset exceeds its sub-block: " + nextPointer);
        }
        block = blockManager.readBlock(nextPointer.blockId());
        long encodedNextBlock = littleEndianLong(block, subBlockStart);
        int initialOffset = nextPointer.offset();
        offset = subBlockStart + Math.max(initialOffset, MetadataBlockLayout.NEXT_BLOCK_POINTER_SIZE);
        capacity = subBlockStart + metadataBlockSize;
        if (offset > capacity) {
            throw new StorageFormatException("DuckDB metadata pointer starts beyond its sub-block: " + nextPointer);
        }
        nextPointer = encodedNextBlock == MetaBlockPointer.INVALID_BLOCK_POINTER
                ? MetaBlockPointer.invalid()
                : new MetaBlockPointer(encodedNextBlock, 0);
    }

    private static long littleEndianLong(byte[] bytes, int offset) {
        return (long) Byte.toUnsignedInt(bytes[offset])
                | (long) Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | (long) Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | (long) Byte.toUnsignedInt(bytes[offset + 3]) << 24
                | (long) Byte.toUnsignedInt(bytes[offset + 4]) << 32
                | (long) Byte.toUnsignedInt(bytes[offset + 5]) << 40
                | (long) Byte.toUnsignedInt(bytes[offset + 6]) << 48
                | (long) Byte.toUnsignedInt(bytes[offset + 7]) << 56;
    }
}
