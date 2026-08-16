package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;

import java.util.List;
import java.util.Objects;

/**
 * Bounded V2 checkpoint catalog serializer. It appends unpublished metadata blocks only: database
 * header, free-list, catalog visibility and WAL publication remain outside this low-level writer.
 */
public final class DuckDbCheckpointMetadataWriter implements AutoCloseable {
    private final MetadataChainWriter chain;
    private final DuckDbBinaryMetadataWriter writer;
    private boolean closed;

    public DuckDbCheckpointMetadataWriter(SingleFileBlockManager blockManager) {
        chain = new MetadataChainWriter(Objects.requireNonNull(blockManager, "blockManager"));
        writer = new DuckDbBinaryMetadataWriter(chain);
    }

    public MetaBlockPointer writeCheckpoint(List<? extends Entry> entries) {
        requireOpen();
        Objects.requireNonNull(entries, "entries");
        MetaBlockPointer root = writer.currentPointer();
        writer.beginObject();
        writer.beginProperty(DuckDbCheckpointEnvelopeReader.CATALOG_ENTRIES_FIELD_ID);
        writer.writeUnsignedLeb128(entries.size());
        for (Entry entry : entries) writeEntry(Objects.requireNonNull(entry, "entry"));
        writer.endObject();
        return root;
    }

    public void flush() {
        requireOpen();
        chain.flush();
        closed = true;
    }

    @Override
    public void close() {
        if (!closed) flush();
    }

    private void writeEntry(Entry entry) {
        writer.beginObject();
        writer.beginProperty(DuckDbCheckpointEnvelopeReader.CATALOG_TYPE_FIELD_ID);
        writer.writeUnsignedLeb128(entry.type().nativeTag());
        writer.beginProperty(DuckDbCheckpointEnvelopeReader.CREATE_INFO_FIELD_ID);
        writer.writeBoolean(true);
        if (entry instanceof Schema schema) writeSchema(schema.createInfo());
        else if (entry instanceof Sequence sequence) writeSequence(sequence.createInfo());
        else if (entry instanceof Table table) writeTable(table.entry());
        else throw new StorageFormatException("DuckDB checkpoint catalog entry type is unsupported: " + entry.type());
        writer.endObject();
    }

    private void writeSchema(DuckDbSchemaCreateInfo info) {
        writer.beginObject();
        writeCommon(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SCHEMA, info.qualifiedNamePath(), info.temporary(),
                info.internal(), info.onConflict().nativeTag(), info.sql(), info.extensionName());
        writer.endObject();
    }

    private void writeSequence(DuckDbSequenceCreateInfo info) {
        writer.beginObject();
        writeCommon(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SEQUENCE, info.qualifiedNamePath(),
                info.temporary(), info.internal(), info.onConflict().nativeTag(), info.sql(), info.extensionName());
        optionalString(200, info.sequenceName());
        optionalUnsigned(201, info.usageCount(), 0);
        optionalSigned(202, info.increment(), 1);
        optionalSigned(203, info.minValue(), 1);
        optionalSigned(204, info.maxValue(), Long.MAX_VALUE);
        optionalSigned(205, info.startValue(), 1);
        if (info.cycle()) {
            writer.beginProperty(206);
            writer.writeBoolean(true);
        }
        if (info.lastValue().isPresent()) {
            writer.beginProperty(207);
            writer.writeBoolean(true);
            writer.writeSignedLeb128(info.lastValue().getAsLong());
        }
        writer.endObject();
    }

    private void writeTable(DuckDbTableEntryEnvelope entry) {
        DuckDbTableCreateInfo info = entry.createInfo();
        writer.beginObject();
        writeCommon(DuckDbCheckpointEnvelopeReader.CatalogEntryType.TABLE, info.qualifiedNamePath(), info.temporary(),
                info.internal(), info.onConflict().nativeTag(), info.sql(), info.extensionName());
        optionalString(200, info.tableName());
        writer.beginProperty(201);
        writeColumns(info.columns());
        writer.endObject();

        if (Long.compareUnsigned(entry.tablePointer().offset(), 0xffff_ffffL) > 0) {
            throw new StorageFormatException("DuckDB table metadata pointer offset exceeds uint32");
        }
        writer.beginProperty(101);
        writer.beginObject();
        if (entry.tablePointer().packedBlockPointer() != 0) {
            writer.beginProperty(100);
            writer.writeUnsignedLeb128(entry.tablePointer().packedBlockPointer());
        }
        if (entry.tablePointer().offset() != 0) {
            writer.beginProperty(101);
            writer.writeUnsignedLeb128(entry.tablePointer().offset());
        }
        writer.endObject();
        writer.beginProperty(102);
        writer.writeUnsignedLeb128(entry.totalRows());
        if (entry.nextRowId() != entry.totalRows()) {
            writer.beginProperty(105);
            writer.writeUnsignedLeb128(entry.nextRowId());
        }
    }

    private void writeCommon(DuckDbCheckpointEnvelopeReader.CatalogEntryType type, List<String> path,
                             boolean temporary, boolean internal, int conflict, String sql, String extensionName) {
        writer.beginProperty(100);
        writer.writeUnsignedLeb128(type.nativeTag());
        if (temporary) {
            writer.beginProperty(103);
            writer.writeBoolean(true);
        }
        if (internal) {
            writer.beginProperty(104);
            writer.writeBoolean(true);
        }
        writer.beginProperty(105);
        writer.writeUnsignedLeb128(conflict);
        optionalString(106, sql);
        optionalString(110, extensionName);
        writer.beginProperty(111);
        writer.beginObject();
        if (!path.isEmpty()) {
            writer.beginProperty(100);
            writer.writeUnsignedLeb128(path.size());
            for (String part : path) writer.writeString(Objects.requireNonNull(part, "qualified name component"));
        }
        writer.endObject();
    }

    private void writeColumns(List<DuckDbTableCreateInfo.Column> columns) {
        writer.beginObject();
        if (!columns.isEmpty()) {
            writer.beginProperty(100);
            writer.writeUnsignedLeb128(columns.size());
            for (DuckDbTableCreateInfo.Column column : columns) writeColumn(column);
        }
        writer.endObject();
    }

    private void writeColumn(DuckDbTableCreateInfo.Column column) {
        Objects.requireNonNull(column, "column");
        if (column.type() != DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN
                && column.type() != DuckDbTableCreateInfo.ScalarLogicalType.INTEGER
                && column.type() != DuckDbTableCreateInfo.ScalarLogicalType.BIGINT) {
            throw new StorageFormatException("DuckDB table metadata writer logical type is unsupported: " + column.type());
        }
        if (column.category() != DuckDbTableCreateInfo.Category.STANDARD) {
            throw new StorageFormatException("DuckDB table metadata writer generated columns are unsupported");
        }
        if (column.compression() != DuckDbTableCreateInfo.Compression.AUTO
                && column.compression() != DuckDbTableCreateInfo.Compression.UNCOMPRESSED) {
            throw new StorageFormatException("DuckDB table metadata writer compression is unsupported: " + column.compression());
        }
        writer.beginObject();
        optionalString(100, column.name());
        writer.beginProperty(101);
        writer.beginObject();
        writer.beginProperty(100);
        writer.writeUnsignedLeb128(column.type().nativeTag());
        writer.endObject();
        writer.beginProperty(103);
        writer.writeUnsignedLeb128(column.category().nativeTag());
        writer.beginProperty(104);
        writer.writeUnsignedLeb128(column.compression().nativeTag());
        writer.endObject();
    }

    private void optionalString(int field, String value) {
        if (!Objects.requireNonNull(value, "value").isEmpty()) {
            writer.beginProperty(field);
            writer.writeString(value);
        }
    }

    private void optionalUnsigned(int field, long value, long defaultValue) {
        if (value != defaultValue) {
            writer.beginProperty(field);
            writer.writeUnsignedLeb128(value);
        }
    }

    private void optionalSigned(int field, long value, long defaultValue) {
        if (value != defaultValue) {
            writer.beginProperty(field);
            writer.writeSignedLeb128(value);
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("DuckDB checkpoint metadata writer is flushed");
    }

    public sealed interface Entry permits Schema, Sequence, Table {
        DuckDbCheckpointEnvelopeReader.CatalogEntryType type();
    }

    public record Schema(DuckDbSchemaCreateInfo createInfo) implements Entry {
        public Schema {
            Objects.requireNonNull(createInfo, "createInfo");
        }
        @Override public DuckDbCheckpointEnvelopeReader.CatalogEntryType type() {
            return DuckDbCheckpointEnvelopeReader.CatalogEntryType.SCHEMA;
        }
    }

    public record Sequence(DuckDbSequenceCreateInfo createInfo) implements Entry {
        public Sequence {
            Objects.requireNonNull(createInfo, "createInfo");
        }
        @Override public DuckDbCheckpointEnvelopeReader.CatalogEntryType type() {
            return DuckDbCheckpointEnvelopeReader.CatalogEntryType.SEQUENCE;
        }
    }

    public record Table(DuckDbTableEntryEnvelope entry) implements Entry {
        public Table {
            Objects.requireNonNull(entry, "entry");
        }
        @Override public DuckDbCheckpointEnvelopeReader.CatalogEntryType type() {
            return DuckDbCheckpointEnvelopeReader.CatalogEntryType.TABLE;
        }
    }
}
