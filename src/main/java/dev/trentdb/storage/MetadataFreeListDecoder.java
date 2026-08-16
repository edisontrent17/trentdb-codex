package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact decoder for the fixed-width free-list root read by DuckDB's SingleFileBlockManager.
 *
 * <p>It mirrors {@code LoadFreeList} followed by {@code MetadataManager::Read}: {@code uint64}
 * counts, {@code int64} block ids, {@code uint32} multi-use counts, and per-metadata-block
 * {@code int64} free-sub-block bitmasks. It deliberately does not enter the BinaryDeserializer
 * field/object/list format used by the catalog checkpoint payload.</p>
 */
public final class MetadataFreeListDecoder {
    private MetadataFreeListDecoder() {
    }

    public static MetadataFreeList decodeActiveFreeList(SingleFileBlockManager blockManager) {
        long root = blockManager.activeHeader().freeList();
        return root == StorageFormat.INVALID_BLOCK
                ? MetadataFreeList.empty()
                : decode(blockManager, new MetaBlockPointer(root, 0));
    }

    public static MetadataFreeList decode(SingleFileBlockManager blockManager, MetaBlockPointer root) {
        if (blockManager == null) {
            throw new IllegalArgumentException("blockManager must not be null");
        }
        if (root == null || !root.isValid()) {
            return MetadataFreeList.empty();
        }
        long blockLimit = blockManager.activeHeader().blockCount();
        MetadataChainReader reader = new MetadataChainReader(blockManager, root);

        long freeBlockCount = readCount(reader, "free block", blockLimit);
        List<Long> freeBlocks = new ArrayList<>(Math.toIntExact(freeBlockCount));
        for (long index = 0; index < freeBlockCount; index++) {
            freeBlocks.add(readBlockId(reader, "free block", blockLimit));
        }

        long multiUseCount = readCount(reader, "multi-use block", blockLimit);
        Map<Long, Long> multiUseBlocks = new LinkedHashMap<>();
        for (long index = 0; index < multiUseCount; index++) {
            long blockId = readBlockId(reader, "multi-use block", blockLimit);
            long usageCount = Integer.toUnsignedLong(reader.readInt());
            multiUseBlocks.put(blockId, usageCount);
        }

        long metadataBlockCount = readCount(reader, "metadata block", blockLimit);
        List<MetadataFreeList.MetadataBlockFreeList> metadataBlocks = new ArrayList<>(
                Math.toIntExact(metadataBlockCount));
        for (long index = 0; index < metadataBlockCount; index++) {
            long blockId = readBlockId(reader, "metadata block", blockLimit);
            long freeSubBlocks = reader.readLong();
            metadataBlocks.add(new MetadataFreeList.MetadataBlockFreeList(blockId,
                    freeSubBlockIndexes(freeSubBlocks)));
        }
        return new MetadataFreeList(freeBlocks, multiUseBlocks, metadataBlocks);
    }

    private static long readCount(MetadataChainReader reader, String name, long maximum) {
        long count = reader.readLong();
        if (count < 0 || count > maximum || count > Integer.MAX_VALUE) {
            throw new StorageFormatException("Invalid DuckDB " + name + " count " + count
                    + "; expected a value in [0, " + Math.min(maximum, Integer.MAX_VALUE) + "]");
        }
        return count;
    }

    private static long readBlockId(MetadataChainReader reader, String name, long blockLimit) {
        long blockId = reader.readLong();
        if (blockId < 0 || blockId >= blockLimit) {
            throw new StorageFormatException("DuckDB " + name + " id is outside the committed block range: " + blockId);
        }
        return blockId;
    }

    private static List<Integer> freeSubBlockIndexes(long bitset) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 63; index >= 0; index--) {
            if ((bitset & (1L << index)) != 0) {
                indexes.add(index);
            }
        }
        return indexes;
    }
}
