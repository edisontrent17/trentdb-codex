package dev.trentdb.storage;

import java.util.List;
import java.util.Map;

/**
 * Decoded V2.0 free-list root, before catalog or table metadata is interpreted.
 *
 * <p>The metadata block descriptors retain DuckDB's descending sub-block-index order. The root
 * contains no serializer object framing: DuckDB's open path reads these fixed-width values
 * directly from {@code MetadataReader} before catalog checkpoint deserialization begins.</p>
 */
public record MetadataFreeList(List<Long> freeBlocks, Map<Long, Long> multiUseBlockCounts,
                               List<MetadataBlockFreeList> metadataBlocks) {
    public MetadataFreeList {
        freeBlocks = List.copyOf(freeBlocks);
        multiUseBlockCounts = Map.copyOf(multiUseBlockCounts);
        metadataBlocks = List.copyOf(metadataBlocks);
    }

    public static MetadataFreeList empty() {
        return new MetadataFreeList(List.of(), Map.of(), List.of());
    }

    /** A block registered by DuckDB's MetadataManager and its currently free metadata sub-blocks. */
    public record MetadataBlockFreeList(long blockId, List<Integer> freeSubBlockIndexes) {
        public MetadataBlockFreeList {
            freeSubBlockIndexes = List.copyOf(freeSubBlockIndexes);
        }
    }
}
