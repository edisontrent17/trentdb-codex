package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.MetadataBlockLayout;
import dev.trentdb.storage.format.StorageFormatException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Buffered writer for DuckDB V2 metadata sub-block chains.
 *
 * <p>It deliberately appends ordinary checksum-protected blocks only when {@link #flush()} is
 * called. The returned pointers are therefore low-level, unpublished metadata pointers; changing
 * a database header/checkpoint to reference them is outside this class.</p>
 */
public final class MetadataChainWriter implements AutoCloseable {
    private final SingleFileBlockManager blockManager;
    private final long firstBlockId;
    private final int metadataBlockSize;
    private final List<byte[]> blocks = new ArrayList<>();
    private int subBlockIndex = -1;
    private int offset;
    private boolean flushed;

    public MetadataChainWriter(SingleFileBlockManager blockManager) {
        this.blockManager = Objects.requireNonNull(blockManager, "blockManager");
        this.firstBlockId = blockManager.activeHeader().blockCount();
        this.metadataBlockSize = MetadataBlockLayout.metadataBlockSize(blockManager.usableBlockSize());
        nextSubBlock();
    }

    public MetaBlockPointer currentPointer() {
        requireOpen();
        if (offset == subBlockStart() + metadataBlockSize) {
            nextSubBlock();
        }
        return pointer(subBlockIndex, offset - subBlockStart());
    }

    public MetaBlockPointer firstPointer() {
        return pointer(0, MetadataBlockLayout.NEXT_BLOCK_POINTER_SIZE);
    }

    public void writeByte(int value) {
        write(new byte[] {(byte) value});
    }

    public void write(byte[] source) {
        Objects.requireNonNull(source, "source");
        requireOpen();
        int sourceOffset = 0;
        while (sourceOffset < source.length) {
            int available = subBlockStart() + metadataBlockSize - offset;
            if (available == 0) {
                nextSubBlock();
                continue;
            }
            int copied = Math.min(available, source.length - sourceOffset);
            System.arraycopy(source, sourceOffset, block(), offset, copied);
            offset += copied;
            sourceOffset += copied;
        }
    }

    /** Appends all buffered storage blocks and makes their checksummed block envelopes durable. */
    public void flush() {
        requireOpen();
        if (blockManager.activeHeader().blockCount() != firstBlockId) {
            throw new StorageFormatException("DuckDB metadata chain writer lost exclusive append position");
        }
        for (int index = 0; index < blocks.size(); index++) {
            blockManager.writeBlock(firstBlockId + index, blocks.get(index));
        }
        flushed = true;
    }

    @Override
    public void close() {
        if (!flushed) {
            flush();
        }
    }

    private void nextSubBlock() {
        int previous = subBlockIndex;
        subBlockIndex++;
        int blockIndex = subBlockIndex / MetadataBlockLayout.METADATA_BLOCK_COUNT;
        if (blockIndex == blocks.size()) {
            blocks.add(new byte[blockManager.usableBlockSize()]);
        }
        int start = subBlockStart();
        putLittleEndianLong(block(), start, MetaBlockPointer.INVALID_BLOCK_POINTER);
        offset = start + MetadataBlockLayout.NEXT_BLOCK_POINTER_SIZE;
        if (previous >= 0) {
            int previousBlock = previous / MetadataBlockLayout.METADATA_BLOCK_COUNT;
            int previousIndex = previous % MetadataBlockLayout.METADATA_BLOCK_COUNT;
            int previousStart = previousIndex * metadataBlockSize;
            putLittleEndianLong(blocks.get(previousBlock), previousStart, pointer(subBlockIndex, 0).blockPointer());
        }
    }

    private MetaBlockPointer pointer(int globalSubBlockIndex, int pointerOffset) {
        int blockIndex = globalSubBlockIndex / MetadataBlockLayout.METADATA_BLOCK_COUNT;
        int subBlock = globalSubBlockIndex % MetadataBlockLayout.METADATA_BLOCK_COUNT;
        return MetaBlockPointer.of(firstBlockId + blockIndex, subBlock, pointerOffset);
    }

    private byte[] block() {
        return blocks.get(subBlockIndex / MetadataBlockLayout.METADATA_BLOCK_COUNT);
    }

    private int subBlockStart() {
        return (subBlockIndex % MetadataBlockLayout.METADATA_BLOCK_COUNT) * metadataBlockSize;
    }

    private void requireOpen() {
        if (flushed) {
            throw new IllegalStateException("DuckDB metadata chain writer is already flushed");
        }
    }

    private static void putLittleEndianLong(byte[] target, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            target[offset + index] = (byte) (value >>> (Byte.SIZE * index));
        }
    }
}
