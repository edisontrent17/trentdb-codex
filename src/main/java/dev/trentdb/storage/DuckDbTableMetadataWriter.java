package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Low-level V2 metadata serializer for the primitive table structures currently supported by the
 * storage reader. It does not publish a catalog or checkpoint root.
 */
public final class DuckDbTableMetadataWriter implements AutoCloseable {
    private static final int COMPRESSION_UNCOMPRESSED = 1;

    private final SingleFileBlockManager blockManager;
    private final MetadataChainWriter chain;
    private final DuckDbBinaryMetadataWriter writer;
    private boolean closed;

    public DuckDbTableMetadataWriter(SingleFileBlockManager blockManager) {
        this.blockManager = Objects.requireNonNull(blockManager, "blockManager");
        this.chain = new MetadataChainWriter(blockManager);
        this.writer = new DuckDbBinaryMetadataWriter(chain);
    }

    /** Serializes one PersistentColumnData primitive envelope and returns its metadata-chain pointer. */
    public DuckDbTableEntryEnvelope.MetaPointer writePrimitiveColumnMetadata(DuckDbPrimitiveColumnMetadata metadata) {
        requireOpen();
        Objects.requireNonNull(metadata, "metadata");
        requirePrimitive(metadata.type());
        MetaBlockPointer pointer = writer.currentPointer();
        writeColumn(metadata.type(), metadata.dataSegments(), metadata.validitySegments(), false);
        return new DuckDbTableEntryEnvelope.MetaPointer(pointer.blockPointer(), Integer.toUnsignedLong(pointer.offset()));
    }

    /** Serializes TableStatistics followed by the V2 RowGroup count and header array. */
    public MetaBlockPointer writeRowGroups(DuckDbRowGroupHeaders rowGroups) {
        requireOpen();
        Objects.requireNonNull(rowGroups, "rowGroups");
        MetaBlockPointer pointer = writer.currentPointer();
        writeTableStatistics(rowGroups.statistics());
        writer.writeUnsignedLongLittleEndian(rowGroups.groups().size());
        for (DuckDbRowGroupHeaders.Header header : rowGroups.groups()) {
            writeRowGroup(header);
        }
        return pointer;
    }

    /** Makes all buffered metadata blocks visible as ordinary blocks, but does not publish a root header. */
    public void flush() {
        requireOpen();
        chain.flush();
        closed = true;
    }

    @Override
    public void close() {
        if (!closed) {
            flush();
        }
    }

    private void writeColumn(DuckDbTableCreateInfo.ScalarLogicalType type,
                             List<DuckDbPrimitiveColumnMetadata.Segment> data,
                             List<DuckDbPrimitiveColumnMetadata.Segment> validity,
                             boolean validityColumn) {
        writer.beginObject();
        if (!data.isEmpty()) {
            writer.beginProperty(100);
            writer.writeUnsignedLeb128(data.size());
            for (DuckDbPrimitiveColumnMetadata.Segment segment : data) {
                writeSegment(type, segment, validityColumn);
            }
        }
        if (!validityColumn) {
            writer.beginProperty(101);
            writeColumn(type, validity, List.of(), true);
        }
        writer.endObject();
    }

    private void writeSegment(DuckDbTableCreateInfo.ScalarLogicalType type,
                              DuckDbPrimitiveColumnMetadata.Segment segment, boolean validity) {
        Objects.requireNonNull(segment, "segment");
        if (segment.tupleCount() <= 0 || segment.tupleCount() > DuckDbPrimitivePayloadReader.VECTOR_SIZE) {
            throw new StorageFormatException("DuckDB primitive metadata segment tuple count is unsupported: "
                    + segment.tupleCount());
        }
        if (segment.compressionType() != COMPRESSION_UNCOMPRESSED) {
            throw new StorageFormatException("DuckDB primitive metadata writer compression is unsupported: "
                    + segment.compressionType());
        }
        long blockId = segment.blockPointer().blockId();
        long offset = segment.blockPointer().offset();
        if (blockId < 0 || blockId >= blockManager.activeHeader().blockCount()
                || offset < 0 || offset > blockManager.usableBlockSize()) {
            throw new StorageFormatException("DuckDB primitive metadata segment pointer is outside committed blocks");
        }
        writer.beginObject();
        writer.beginProperty(101);
        writer.writeUnsignedLeb128(segment.tupleCount());
        writer.beginProperty(102);
        writer.beginObject();
        writer.beginProperty(100);
        writer.writeSignedLeb128(blockId);
        if (offset != 0) {
            writer.beginProperty(101);
            writer.writeUnsignedLeb128(offset);
        }
        writer.endObject();
        writer.beginProperty(103);
        writer.writeUnsignedLeb128(segment.compressionType());
        writer.beginProperty(104);
        writeStatistics(type, segment.statistics(), validity);
        writer.endObject();
    }

    private void writeTableStatistics(DuckDbTableStatistics statistics) {
        Objects.requireNonNull(statistics, "statistics");
        writer.beginObject();
        writer.beginProperty(100);
        writer.writeUnsignedLeb128(statistics.columns().size());
        for (DuckDbTableStatistics.Primitive primitive : statistics.columns()) {
            DuckDbTableCreateInfo.ScalarLogicalType type = switch (primitive.kind()) {
                case BOOLEAN -> DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN;
                case INTEGER -> DuckDbTableCreateInfo.ScalarLogicalType.INTEGER;
                case BIGINT -> DuckDbTableCreateInfo.ScalarLogicalType.BIGINT;
            };
            writeStatistics(type, new DuckDbPrimitiveColumnMetadata.Statistics(primitive.hasNull(),
                    primitive.hasNoNull(), primitive.distinctCount(), primitive.min(), primitive.max()), false);
        }
        writer.endObject();
    }

    private void writeStatistics(DuckDbTableCreateInfo.ScalarLogicalType type,
                                 DuckDbPrimitiveColumnMetadata.Statistics statistics, boolean validity) {
        Objects.requireNonNull(statistics, "statistics");
        validateBounds(type, statistics.min(), statistics.max(), validity);
        writer.beginObject();
        writer.beginProperty(100);
        writer.writeBoolean(statistics.hasNull());
        writer.beginProperty(101);
        writer.writeBoolean(statistics.hasNoNull());
        writer.beginProperty(102);
        writer.writeUnsignedLeb128(statistics.distinctCount());
        writer.beginProperty(103);
        writer.beginObject();
        if (!validity) {
            writeBound(type, 200, statistics.min());
            writeBound(type, 201, statistics.max());
        }
        writer.endObject();
        writer.endObject();
    }

    private void writeBound(DuckDbTableCreateInfo.ScalarLogicalType type, int field, OptionalLong bound) {
        writer.beginProperty(field);
        writer.beginObject();
        writer.beginProperty(100);
        writer.writeBoolean(bound.isPresent());
        if (bound.isPresent()) {
            writer.beginProperty(101);
            if (type == DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN) {
                writer.writeBoolean(bound.getAsLong() != 0);
            } else {
                writer.writeSignedLeb128(bound.getAsLong());
            }
        }
        writer.endObject();
    }

    private void writeRowGroup(DuckDbRowGroupHeaders.Header header) {
        Objects.requireNonNull(header, "header");
        writer.beginObject();
        writer.beginProperty(100);
        writer.writeUnsignedLeb128(header.rowStart());
        writer.beginProperty(101);
        writer.writeUnsignedLeb128(header.tupleCount());
        writer.beginProperty(102);
        writeMetaPointers(header.dataPointers());
        writer.beginProperty(103);
        writeMetaPointers(header.deletePointers());

        boolean perColumn = header.hasPerColumnMetadataBlocks();
        writer.beginProperty(104);
        writer.writeBoolean(perColumn || header.hasMetadataBlocks());
        if (!header.extraMetadataBlocks().isEmpty()) {
            writer.beginProperty(105);
            writer.writeUnsignedLeb128(header.extraMetadataBlocks().size());
            for (Long block : header.extraMetadataBlocks()) {
                if (block == null || block < 0) {
                    throw new StorageFormatException("DuckDB row group metadata block id is invalid");
                }
                writer.writeUnsignedLeb128(block);
            }
        }
        writer.beginProperty(106);
        writer.writeBoolean(perColumn);
        if (!header.perColumnMetadataBlocks().isEmpty()) {
            writer.beginProperty(107);
            writer.writeUnsignedLeb128(header.perColumnMetadataBlocks().size());
            for (DuckDbRowGroupHeaders.PerColumnMetadataBlock block : header.perColumnMetadataBlocks()) {
                if (block.index() < 0 || (block.index() & Long.MIN_VALUE) != 0) {
                    throw new StorageFormatException("DuckDB row group per-column metadata index is invalid");
                }
                writer.writeUnsignedLeb128((block.columnIndex() ? Long.MIN_VALUE : 0) | block.index());
            }
        }
        writer.endObject();
    }

    private void writeMetaPointers(List<DuckDbTableEntryEnvelope.MetaPointer> pointers) {
        writer.writeUnsignedLeb128(pointers.size());
        for (DuckDbTableEntryEnvelope.MetaPointer pointer : pointers) {
            if (pointer == null || Long.compareUnsigned(pointer.offset(), 0xffff_ffffL) > 0) {
                throw new StorageFormatException("DuckDB metadata pointer offset exceeds uint32");
            }
            writer.beginObject();
            if (pointer.packedBlockPointer() != 0) {
                writer.beginProperty(100);
                writer.writeUnsignedLeb128(pointer.packedBlockPointer());
            }
            if (pointer.offset() != 0) {
                writer.beginProperty(101);
                writer.writeUnsignedLeb128(pointer.offset());
            }
            writer.endObject();
        }
    }

    private static void validateBounds(DuckDbTableCreateInfo.ScalarLogicalType type, OptionalLong min,
                                       OptionalLong max, boolean validity) {
        requirePrimitive(type);
        if (validity && (min.isPresent() || max.isPresent())) {
            throw new StorageFormatException("DuckDB validity statistics must not have primitive bounds");
        }
        if (min.isPresent() != max.isPresent()) {
            throw new StorageFormatException("DuckDB primitive statistics must have both min and max or neither");
        }
        if (min.isPresent() && min.getAsLong() > max.getAsLong()) {
            throw new StorageFormatException("DuckDB primitive statistics min exceeds max");
        }
        if (type == DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN
                && ((min.isPresent() && (min.getAsLong() < 0 || min.getAsLong() > 1))
                || (max.isPresent() && (max.getAsLong() < 0 || max.getAsLong() > 1)))) {
            throw new StorageFormatException("DuckDB BOOLEAN statistics bound must be 0 or 1");
        }
    }

    private static void requirePrimitive(DuckDbTableCreateInfo.ScalarLogicalType type) {
        if (type != DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN
                && type != DuckDbTableCreateInfo.ScalarLogicalType.INTEGER
                && type != DuckDbTableCreateInfo.ScalarLogicalType.BIGINT) {
            throw new StorageFormatException("DuckDB primitive metadata type is unsupported: " + type);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("DuckDB table metadata writer is already flushed");
        }
    }
}
