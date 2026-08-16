package dev.trentdb.storage;

import dev.trentdb.storage.format.StorageFormatException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Writes one DuckDB V2 uncompressed fixed-width primitive vector as raw block payloads. */
public final class DuckDbPrimitivePayloadWriter {
    public static final int VECTOR_SIZE = DuckDbPrimitivePayloadReader.VECTOR_SIZE;
    private static final int COMPRESSION_UNCOMPRESSED = 1;
    private static final int VALIDITY_BYTES_PER_VECTOR = VECTOR_SIZE / Byte.SIZE;

    private final SingleFileBlockManager blockManager;

    public DuckDbPrimitivePayloadWriter(SingleFileBlockManager blockManager) {
        this.blockManager = Objects.requireNonNull(blockManager, "blockManager");
    }

    /**
     * Appends one non-empty vector. Null payload slots are zero-filled; their validity bits are clear.
     * This remains below checkpoint/catalog publication: it only creates raw blocks and descriptors.
     */
    public EncodedVector write(DuckDbTableCreateInfo.ScalarLogicalType type, List<Long> values) {
        return write(type, values, false);
    }

    /**
     * Writes one vector, optionally retaining an all-valid validity segment so a multi-vector
     * column has descriptor-aligned validity blocks whenever any sibling vector contains NULL.
     */
    public EncodedVector write(DuckDbTableCreateInfo.ScalarLogicalType type, List<Long> values,
                               boolean requireValiditySegment) {
        requireSupportedType(type);
        Objects.requireNonNull(values, "values");
        int count = values.size();
        if (count == 0 || count > VECTOR_SIZE) {
            throw new StorageFormatException("DuckDB primitive payload writer requires 1.." + VECTOR_SIZE
                    + " values: " + count);
        }

        int width = width(type);
        long dataByteCount = Math.multiplyExact((long) count, width);
        if (dataByteCount > blockManager.usableBlockSize()) {
            throw new StorageFormatException("DuckDB primitive payload writer would cross a block boundary");
        }

        byte[] data = new byte[Math.toIntExact(dataByteCount)];
        byte[] validity = new byte[VALIDITY_BYTES_PER_VECTOR];
        Arrays.fill(validity, (byte) 0xFF);
        boolean hasNull = false;
        boolean hasValue = false;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;

        for (int index = 0; index < count; index++) {
            Long value = values.get(index);
            if (value == null) {
                hasNull = true;
                validity[index / Byte.SIZE] &= (byte) ~(1 << (index % Byte.SIZE));
                continue;
            }
            long primitive = validateValue(type, value);
            putLittleEndian(data, index * width, width, primitive);
            hasValue = true;
            min = Math.min(min, primitive);
            max = Math.max(max, primitive);
        }
        if (validity.length > blockManager.usableBlockSize()) {
            throw new StorageFormatException("DuckDB primitive validity payload would cross a block boundary");
        }

        DuckDbPrimitiveColumnMetadata.Statistics dataStatistics = new DuckDbPrimitiveColumnMetadata.Statistics(
                hasNull, hasValue, 0, hasValue ? OptionalLong.of(min) : OptionalLong.empty(),
                hasValue ? OptionalLong.of(max) : OptionalLong.empty());
        long dataBlock = blockManager.activeHeader().blockCount();
        blockManager.writeBlock(dataBlock, data);
        DuckDbPrimitiveColumnMetadata.Segment dataSegment = new DuckDbPrimitiveColumnMetadata.Segment(count,
                new DuckDbPrimitiveColumnMetadata.BlockPointer(dataBlock, 0), COMPRESSION_UNCOMPRESSED,
                dataStatistics);

        if (!hasNull && !requireValiditySegment) {
            return new EncodedVector(type, dataSegment, Optional.empty());
        }
        long validityBlock = blockManager.activeHeader().blockCount();
        blockManager.writeBlock(validityBlock, validity);
        DuckDbPrimitiveColumnMetadata.Segment validitySegment = new DuckDbPrimitiveColumnMetadata.Segment(count,
                new DuckDbPrimitiveColumnMetadata.BlockPointer(validityBlock, 0), COMPRESSION_UNCOMPRESSED,
                new DuckDbPrimitiveColumnMetadata.Statistics(hasNull, hasValue, 0, OptionalLong.empty(),
                        OptionalLong.empty()));
        return new EncodedVector(type, dataSegment, Optional.of(validitySegment));
    }

    /** A data and optional validity DataPointer envelope ready for PersistentColumnData serialization. */
    public record EncodedVector(DuckDbTableCreateInfo.ScalarLogicalType type,
                                DuckDbPrimitiveColumnMetadata.Segment dataSegment,
                                Optional<DuckDbPrimitiveColumnMetadata.Segment> validitySegment) {
        public EncodedVector {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(dataSegment, "dataSegment");
            Objects.requireNonNull(validitySegment, "validitySegment");
        }
    }

    private static void requireSupportedType(DuckDbTableCreateInfo.ScalarLogicalType type) {
        if (type != DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN
                && type != DuckDbTableCreateInfo.ScalarLogicalType.INTEGER
                && type != DuckDbTableCreateInfo.ScalarLogicalType.BIGINT) {
            throw new StorageFormatException("DuckDB primitive payload writer type is unsupported: " + type);
        }
    }

    private static int width(DuckDbTableCreateInfo.ScalarLogicalType type) {
        return type == DuckDbTableCreateInfo.ScalarLogicalType.BIGINT ? Long.BYTES
                : type == DuckDbTableCreateInfo.ScalarLogicalType.INTEGER ? Integer.BYTES : 1;
    }

    private static long validateValue(DuckDbTableCreateInfo.ScalarLogicalType type, long value) {
        if (type == DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN && value != 0 && value != 1) {
            throw new StorageFormatException("DuckDB BOOLEAN payload value must be 0 or 1: " + value);
        }
        if (type == DuckDbTableCreateInfo.ScalarLogicalType.INTEGER
                && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
            throw new StorageFormatException("DuckDB INTEGER payload value is outside int32 range: " + value);
        }
        return value;
    }

    private static void putLittleEndian(byte[] target, int offset, int width, long value) {
        for (int index = 0; index < width; index++) {
            target[offset + index] = (byte) (value >>> (Byte.SIZE * index));
        }
    }
}
