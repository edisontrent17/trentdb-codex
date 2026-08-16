package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded end-to-end V2 checkpoint producer. It creates a new file, writes primitive table blocks
 * and metadata, then atomically selects the catalog root through the inactive database header.
 */
public final class DuckDbV2CheckpointPublisher {
    private DuckDbV2CheckpointPublisher() {
    }

    public static Publication create(Path path, byte[] databaseIdentifier, Checkpoint checkpoint) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(databaseIdentifier, "databaseIdentifier");
        Objects.requireNonNull(checkpoint, "checkpoint");
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, databaseIdentifier)) {
            List<DuckDbCheckpointMetadataWriter.Entry> entries = new ArrayList<>();
            for (DuckDbSchemaCreateInfo schema : checkpoint.schemas()) {
                entries.add(new DuckDbCheckpointMetadataWriter.Schema(schema));
            }
            for (DuckDbSequenceCreateInfo sequence : checkpoint.sequences()) {
                entries.add(new DuckDbCheckpointMetadataWriter.Sequence(sequence));
            }
            for (PrimitiveTable table : checkpoint.tables()) {
                entries.add(new DuckDbCheckpointMetadataWriter.Table(writeTable(manager, table)));
            }
            MetaBlockPointer root;
            try (DuckDbCheckpointMetadataWriter catalog = new DuckDbCheckpointMetadataWriter(manager)) {
                root = catalog.writeCheckpoint(entries);
            }
            // Every data and metadata block has been checksummed, written and forced by its writer.
            manager.publishCheckpoint(root);
            return new Publication(path, root, manager.activeHeader().iteration(), manager.activeHeader().blockCount());
        }
    }

    private static DuckDbTableEntryEnvelope writeTable(SingleFileBlockManager manager, PrimitiveTable table) {
        DuckDbTableCreateInfo info = table.createInfo();
        List<List<Long>> columns = table.values();
        if (info.columns().isEmpty() || info.columns().size() != columns.size()) {
            throw new StorageFormatException("DuckDB primitive checkpoint table column/value count mismatch");
        }
        int rowCount = -1;
        List<DuckDbPrimitiveColumnMetadata> metadata = new ArrayList<>();
        List<DuckDbTableStatistics.Primitive> statistics = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            DuckDbTableCreateInfo.Column column = info.columns().get(index);
            requirePrimitive(column);
            List<Long> values = columns.get(index);
            if (values == null) throw new StorageFormatException("DuckDB primitive checkpoint table column values are null");
            if (rowCount == -1) rowCount = values.size();
            if (rowCount != values.size()) throw new StorageFormatException("DuckDB primitive checkpoint table row counts differ");
            boolean hasNull = values.stream().anyMatch(java.util.Objects::isNull);
            List<DuckDbPrimitiveColumnMetadata.Segment> data = new ArrayList<>();
            List<DuckDbPrimitiveColumnMetadata.Segment> validity = new ArrayList<>();
            DuckDbPrimitivePayloadWriter writer = new DuckDbPrimitivePayloadWriter(manager);
            for (int start = 0; start < values.size(); start += DuckDbPrimitivePayloadWriter.VECTOR_SIZE) {
                int end = Math.min(start + DuckDbPrimitivePayloadWriter.VECTOR_SIZE, values.size());
                DuckDbPrimitivePayloadWriter.EncodedVector encoded = writer.write(column.type(), values.subList(start, end), hasNull);
                data.add(encoded.dataSegment());
                encoded.validitySegment().ifPresent(validity::add);
            }
            metadata.add(new DuckDbPrimitiveColumnMetadata(column.type(), data, validity,
                    DuckDbPrimitiveColumnMetadata.Boundary.BLOCK_PAYLOAD_DECOMPRESSION_UNSUPPORTED));
            statistics.add(statistics(column.type(), values));
        }

        if (rowCount == -1) throw new StorageFormatException("DuckDB primitive checkpoint table has no columns");

        MetaBlockPointer tableRoot;
        try (DuckDbTableMetadataWriter tableWriter = new DuckDbTableMetadataWriter(manager)) {
            List<DuckDbTableEntryEnvelope.MetaPointer> columnPointers = new ArrayList<>();
            for (DuckDbPrimitiveColumnMetadata column : metadata) {
                columnPointers.add(tableWriter.writePrimitiveColumnMetadata(column));
            }
            DuckDbRowGroupHeaders rows = new DuckDbRowGroupHeaders(new DuckDbTableStatistics(statistics),
                    List.of(new DuckDbRowGroupHeaders.Header(0, rowCount, columnPointers, List.of())));
            tableRoot = tableWriter.writeRowGroups(rows);
        }
        return new DuckDbTableEntryEnvelope(info,
                new DuckDbTableEntryEnvelope.MetaPointer(tableRoot.blockPointer(), tableRoot.offset()), rowCount, rowCount,
                DuckDbTableEntryEnvelope.Boundary.TABLE_METADATA_CHAIN_ROW_GROUPS_AND_INDEXES_UNSUPPORTED);
    }

    private static DuckDbTableStatistics.Primitive statistics(DuckDbTableCreateInfo.ScalarLogicalType type,
                                                               List<Long> values) {
        boolean hasNull = false;
        boolean hasValue = false;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long value : values) {
            if (value == null) {
                hasNull = true;
            } else {
                hasValue = true;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return new DuckDbTableStatistics.Primitive(hasNull, hasValue, 0, kind(type),
                hasValue ? java.util.OptionalLong.of(min) : java.util.OptionalLong.empty(),
                hasValue ? java.util.OptionalLong.of(max) : java.util.OptionalLong.empty());
    }

    private static DuckDbTableStatistics.Kind kind(DuckDbTableCreateInfo.ScalarLogicalType type) {
        return switch (type) {
        case BOOLEAN -> DuckDbTableStatistics.Kind.BOOLEAN;
        case INTEGER -> DuckDbTableStatistics.Kind.INTEGER;
        case BIGINT -> DuckDbTableStatistics.Kind.BIGINT;
        default -> throw new StorageFormatException("DuckDB primitive checkpoint type is unsupported: " + type);
        };
    }

    private static void requirePrimitive(DuckDbTableCreateInfo.Column column) {
        if (column.type() != DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN
                && column.type() != DuckDbTableCreateInfo.ScalarLogicalType.INTEGER
                && column.type() != DuckDbTableCreateInfo.ScalarLogicalType.BIGINT) {
            throw new StorageFormatException("DuckDB primitive checkpoint type is unsupported: " + column.type());
        }
        if (column.category() != DuckDbTableCreateInfo.Category.STANDARD
                || (column.compression() != DuckDbTableCreateInfo.Compression.AUTO
                && column.compression() != DuckDbTableCreateInfo.Compression.UNCOMPRESSED)) {
            throw new StorageFormatException("DuckDB primitive checkpoint table shape is unsupported");
        }
    }

    public record Checkpoint(List<DuckDbSchemaCreateInfo> schemas, List<DuckDbSequenceCreateInfo> sequences,
                             List<PrimitiveTable> tables) {
        public Checkpoint {
            schemas = List.copyOf(schemas);
            sequences = List.copyOf(sequences);
            tables = List.copyOf(tables);
        }
    }

    public record PrimitiveTable(DuckDbTableCreateInfo createInfo, List<List<Long>> values) {
        public PrimitiveTable {
            Objects.requireNonNull(createInfo, "createInfo");
            values = List.copyOf(values);
            for (List<Long> value : values) Objects.requireNonNull(value, "column values");
        }
    }

    public record Publication(Path path, MetaBlockPointer root, long iteration, long blockCount) {
    }
}
