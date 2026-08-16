package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Reads the V2.0 checkpoint root and the catalog-entry dispatcher envelope.
 *
 * <p>The pinned DuckDB checkpoint writer emits an object whose field {@value #CATALOG_ENTRIES_FIELD_ID}
 * is a list of entry objects. Each entry starts with field {@value #CATALOG_TYPE_FIELD_ID}, a
 * {@code CatalogType:uint8}, then field {@value #CREATE_INFO_FIELD_ID}, whose payload is a
 * type-specific CreateInfo object. Schema and sequence CreateInfo are decoded; other payload types
 * stop at that boundary because their schemas are not implemented yet.</p>
 */
public final class DuckDbCheckpointEnvelopeReader {
    public static final int CATALOG_TYPE_FIELD_ID = 99;
    public static final int CATALOG_ENTRIES_FIELD_ID = 100;
    public static final int CREATE_INFO_FIELD_ID = 100;

    private final DuckDbBinaryMetadataReader reader;
    private long remainingEntries;
    private boolean rootOpened;
    private boolean payloadBoundaryReached;
    private CatalogEntryType payloadType;

    public DuckDbCheckpointEnvelopeReader(SingleFileBlockManager blockManager, MetaBlockPointer root) {
        if (blockManager == null) {
            throw new IllegalArgumentException("blockManager must not be null");
        }
        if (root == null || !root.isValid()) {
            throw new StorageFormatException("DuckDB checkpoint root is invalid");
        }
        reader = new DuckDbBinaryMetadataReader(new MetadataChainReader(blockManager, root),
                blockManager.activeHeader().storageCompatibility());
    }

    public static DuckDbCheckpointEnvelopeReader openActiveCheckpoint(SingleFileBlockManager blockManager) {
        long root = blockManager.activeHeader().metaBlock();
        if (root == StorageFormat.INVALID_BLOCK) {
            throw new StorageFormatException("DuckDB database has no checkpoint root");
        }
        return new DuckDbCheckpointEnvelopeReader(blockManager, new MetaBlockPointer(root, 0));
    }

    /** Opens the root object and returns its declared number of catalog entry envelopes. */
    public long beginCheckpoint() {
        if (rootOpened) {
            throw new StorageFormatException("DuckDB checkpoint root has already been opened");
        }
        rootOpened = true;
        reader.beginObject();
        reader.beginProperty(CATALOG_ENTRIES_FIELD_ID);
        remainingEntries = reader.beginList();
        if (remainingEntries < 0 || remainingEntries > Integer.MAX_VALUE) {
            throw new StorageFormatException("DuckDB checkpoint catalog entry count exceeds supported bound: "
                    + Long.toUnsignedString(remainingEntries));
        }
        if (remainingEntries == 0) {
            reader.endObject();
        }
        return remainingEntries;
    }

    /**
     * Reads fields 99 and 100 of one native checkpoint entry. The underlying stream is left at the
     * CreateInfo payload so a type-specific decoder must consume it before another entry can be read.
     */
    public CatalogEntryEnvelope readNextEntryEnvelope() {
        if (!rootOpened) {
            throw new StorageFormatException("DuckDB checkpoint root must be opened before reading entries");
        }
        if (payloadBoundaryReached) {
            throw new StorageFormatException("DuckDB catalog entry payload is unsupported; cannot advance envelope reader");
        }
        if (remainingEntries == 0) {
            throw new StorageFormatException("DuckDB checkpoint has no remaining catalog entry envelopes");
        }
        reader.beginObject();
        reader.beginProperty(CATALOG_TYPE_FIELD_ID);
        long rawType = reader.readUnsignedLeb128();
        if (rawType > 0xff) {
            throw new StorageFormatException("DuckDB catalog type tag exceeds uint8: " + rawType);
        }
        CatalogEntryType type = CatalogEntryType.fromNativeTag((int) rawType);
        if (type == null) {
            throw new StorageFormatException("DuckDB checkpoint contains an unrecognized catalog type tag: " + rawType);
        }
        reader.beginProperty(CREATE_INFO_FIELD_ID);
        remainingEntries--;
        payloadBoundaryReached = true;
        payloadType = type;
        return new CatalogEntryEnvelope(type, switch (type) {
        case SCHEMA -> CatalogEntryPayloadOutcome.SCHEMA_CREATE_INFO_AVAILABLE;
        case SEQUENCE -> CatalogEntryPayloadOutcome.SEQUENCE_CREATE_INFO_AVAILABLE;
        case TABLE -> CatalogEntryPayloadOutcome.TABLE_CREATE_INFO_PREFIX_AVAILABLE;
        default -> CatalogEntryPayloadOutcome.UNSUPPORTED_CREATE_INFO_PAYLOAD;
        });
    }

    /** Reads the pending V2.0 {@code SchemaCatalogEntry} CreateInfo payload. */
    public DuckDbSchemaCreateInfo readSchemaCreateInfo() {
        requirePendingPayload(CatalogEntryType.SCHEMA, "SchemaCreateInfo");
        requireNonNullCreateInfo("schema");

        reader.beginObject();
        reader.beginProperty(CREATE_INFO_FIELD_ID);
        requireCreateInfoType(CatalogEntryType.SCHEMA, "schema");
        rejectLegacyQualificationField(101, "catalog", "SchemaCreateInfo");
        rejectLegacyQualificationField(102, "schema", "SchemaCreateInfo");
        boolean temporary = reader.beginOptionalProperty(103) && reader.readBoolean();
        boolean internal = reader.beginOptionalProperty(104) && reader.readBoolean();

        reader.beginProperty(105);
        DuckDbSchemaCreateInfo.OnCreateConflict onConflict =
                DuckDbSchemaCreateInfo.OnCreateConflict.fromNativeTag(reader.readUnsignedLeb128());
        String sql = reader.beginOptionalProperty(106) ? reader.readString() : "";
        rejectUnsupportedNonDefaultField(107, "comment (Value)", "SchemaCreateInfo");
        rejectUnsupportedNonDefaultField(108, "tags (InsertionOrderPreservingMap<string>)", "SchemaCreateInfo");
        rejectUnsupportedNonDefaultField(109, "dependencies (LogicalDependencyList)", "SchemaCreateInfo");
        String extensionName = reader.beginOptionalProperty(110) ? reader.readString() : "";
        List<String> qualifiedNamePath = reader.beginOptionalProperty(111) ? readQualifiedNamePath() : List.of();

        finishCreateInfoEntry();
        return new DuckDbSchemaCreateInfo(qualifiedNamePath, temporary, internal, onConflict, sql, extensionName);
    }

    /**
     * Reads the pending V2.0 {@code SequenceCatalogEntry} CreateInfo payload.
     *
     * <p>The first fields are the common CreateInfo serialization. Fields 200-207 are the pinned
     * sequence serialization; 207 is a nullable {@code optional<int64_t>} when present.</p>
     */
    public DuckDbSequenceCreateInfo readSequenceCreateInfo() {
        requirePendingPayload(CatalogEntryType.SEQUENCE, "SequenceCreateInfo");
        requireNonNullCreateInfo("sequence");

        reader.beginObject();
        reader.beginProperty(CREATE_INFO_FIELD_ID);
        requireCreateInfoType(CatalogEntryType.SEQUENCE, "sequence");
        rejectLegacyQualificationField(101, "catalog", "SequenceCreateInfo");
        rejectLegacyQualificationField(102, "schema", "SequenceCreateInfo");
        boolean temporary = reader.beginOptionalProperty(103) && reader.readBoolean();
        boolean internal = reader.beginOptionalProperty(104) && reader.readBoolean();

        reader.beginProperty(105);
        DuckDbSequenceCreateInfo.OnCreateConflict onConflict =
                DuckDbSequenceCreateInfo.OnCreateConflict.fromNativeTag(reader.readUnsignedLeb128());
        String sql = reader.beginOptionalProperty(106) ? reader.readString() : "";
        rejectUnsupportedNonDefaultField(107, "comment (Value)", "SequenceCreateInfo");
        rejectUnsupportedNonDefaultField(108, "tags (InsertionOrderPreservingMap<string>)", "SequenceCreateInfo");
        rejectUnsupportedNonDefaultField(109, "dependencies (LogicalDependencyList)", "SequenceCreateInfo");
        String extensionName = reader.beginOptionalProperty(110) ? reader.readString() : "";
        List<String> qualifiedNamePath = reader.beginOptionalProperty(111) ? readQualifiedNamePath() : List.of();

        String sequenceName = reader.beginOptionalProperty(200) ? reader.readString() : "";
        long usageCount = reader.beginOptionalProperty(201) ? reader.readUnsignedLeb128() : 0;
        long increment = reader.beginOptionalProperty(202) ? reader.readSignedLeb128() : 1;
        long minValue = reader.beginOptionalProperty(203) ? reader.readSignedLeb128() : 1;
        long maxValue = reader.beginOptionalProperty(204) ? reader.readSignedLeb128() : Long.MAX_VALUE;
        long startValue = reader.beginOptionalProperty(205) ? reader.readSignedLeb128() : 1;
        boolean cycle = reader.beginOptionalProperty(206) && reader.readBoolean();
        OptionalLong lastValue = readOptionalLastValue();

        finishCreateInfoEntry();
        return new DuckDbSequenceCreateInfo(qualifiedNamePath, temporary, internal, onConflict, sql, extensionName,
                sequenceName, usageCount, increment, minValue, maxValue, startValue, cycle, lastValue);
    }

    /**
     * Decodes the V2 CreateTableInfo prefix through its ColumnList, then stops at the enclosing
     * table entry's required metadata pointer (field 101). No table metadata or row data is read.
     */
    public DuckDbTableCreateInfo readTableCreateInfoPrefix() {
        requirePendingPayload(CatalogEntryType.TABLE, "TableCreateInfo");
        requireNonNullCreateInfo("table");
        reader.beginObject();
        reader.beginProperty(CREATE_INFO_FIELD_ID);
        requireCreateInfoType(CatalogEntryType.TABLE, "table");
        rejectLegacyQualificationField(101, "catalog", "TableCreateInfo");
        rejectLegacyQualificationField(102, "schema", "TableCreateInfo");
        boolean temporary = reader.beginOptionalProperty(103) && reader.readBoolean();
        boolean internal = reader.beginOptionalProperty(104) && reader.readBoolean();
        reader.beginProperty(105);
        DuckDbSequenceCreateInfo.OnCreateConflict onConflict =
                DuckDbSequenceCreateInfo.OnCreateConflict.fromNativeTag(reader.readUnsignedLeb128());
        String sql = reader.beginOptionalProperty(106) ? reader.readString() : "";
        rejectUnsupportedNonDefaultField(107, "comment (Value)", "TableCreateInfo");
        rejectUnsupportedNonDefaultField(108, "tags (InsertionOrderPreservingMap<string>)", "TableCreateInfo");
        rejectUnsupportedNonDefaultField(109, "dependencies (LogicalDependencyList)", "TableCreateInfo");
        String extensionName = reader.beginOptionalProperty(110) ? reader.readString() : "";
        List<String> qualifiedNamePath = reader.beginOptionalProperty(111) ? readQualifiedNamePath() : List.of();

        String tableName = reader.beginOptionalProperty(200) ? reader.readString() : "";
        reader.beginProperty(201);
        List<DuckDbTableCreateInfo.Column> columns = readColumnList();
        rejectUnsupportedNonDefaultField(202, "constraints", "TableCreateInfo");
        rejectUnsupportedNonDefaultField(203, "query", "TableCreateInfo");
        rejectUnsupportedNonDefaultField(204, "partition_keys", "TableCreateInfo");
        rejectUnsupportedNonDefaultField(205, "sort_keys", "TableCreateInfo");
        rejectUnsupportedNonDefaultField(206, "options", "TableCreateInfo");
        reader.endObject(); // CreateTableInfo

        // SingleFileTableDataWriter follows with required entry field 101 (MetaBlockPointer).
        // Leave it untouched: its serialization and the table data chain are the next boundary.
        payloadBoundaryReached = true;
        payloadType = CatalogEntryType.TABLE;
        return new DuckDbTableCreateInfo(qualifiedNamePath, temporary, internal, onConflict, sql, extensionName,
                tableName, columns, DuckDbTableCreateInfo.Boundary.TABLE_METADATA_FIELD_101_UNSUPPORTED);
    }

    private List<DuckDbTableCreateInfo.Column> readColumnList() {
        reader.beginObject();
        List<DuckDbTableCreateInfo.Column> columns = new ArrayList<>();
        if (reader.beginOptionalProperty(100)) {
            long count = reader.beginList();
            if (count < 0 || count > Integer.MAX_VALUE) {
                throw new StorageFormatException("DuckDB ColumnList count exceeds supported bound: "
                        + Long.toUnsignedString(count));
            }
            for (int index = 0; index < count; index++) columns.add(readColumnDefinition());
        }
        reader.endObject();
        return List.copyOf(columns);
    }

    private DuckDbTableCreateInfo.Column readColumnDefinition() {
        reader.beginObject();
        String name = reader.beginOptionalProperty(100) ? reader.readString() : "";
        reader.beginProperty(101);
        DuckDbTableCreateInfo.ScalarLogicalType type = readScalarLogicalType();
        rejectUnsupportedNonDefaultField(102, "default/generated expression", "ColumnDefinition");
        reader.beginProperty(103);
        DuckDbTableCreateInfo.Category category;
        try {
            category = DuckDbTableCreateInfo.Category.fromNativeTag(reader.readUnsignedLeb128());
        } catch (IllegalArgumentException exception) {
            throw new StorageFormatException("DuckDB ColumnDefinition has " + exception.getMessage());
        }
        reader.beginProperty(104);
        DuckDbTableCreateInfo.Compression compression;
        try {
            compression = DuckDbTableCreateInfo.Compression.fromNativeTag(reader.readUnsignedLeb128());
        } catch (IllegalArgumentException exception) {
            throw new StorageFormatException("DuckDB ColumnDefinition has " + exception.getMessage());
        }
        rejectUnsupportedNonDefaultField(105, "comment (Value)", "ColumnDefinition");
        rejectUnsupportedNonDefaultField(106, "tags", "ColumnDefinition");
        reader.endObject();
        return new DuckDbTableCreateInfo.Column(name, type, category, compression);
    }

    private DuckDbTableCreateInfo.ScalarLogicalType readScalarLogicalType() {
        reader.beginObject();
        reader.beginProperty(100);
        long typeTag = reader.readUnsignedLeb128();
        DuckDbTableCreateInfo.ScalarLogicalType type;
        try {
            type = DuckDbTableCreateInfo.ScalarLogicalType.fromNativeTag(typeTag);
        } catch (IllegalArgumentException exception) {
            throw new StorageFormatException("DuckDB LogicalType has " + exception.getMessage());
        }
        if (reader.beginOptionalProperty(101)) {
            throw new StorageFormatException("DuckDB LogicalType ExtraTypeInfo field 101 is unsupported");
        }
        reader.endObject();
        return type;
    }

    /** Decodes table entry fields 101-105 after {@link #readTableCreateInfoPrefix()}. */
    public DuckDbTableEntryEnvelope readTableEntryEnvelope() {
        DuckDbTableCreateInfo createInfo = readTableCreateInfoPrefix();
        reader.beginProperty(101);
        reader.beginObject();
        long packed = reader.beginOptionalProperty(100) ? reader.readUnsignedLeb128() : 0;
        long offset = reader.beginOptionalProperty(101) ? reader.readUnsignedLeb128() : 0;
        if (offset > 0xffff_ffffL) throw new StorageFormatException("DuckDB table MetaBlockPointer offset exceeds uint32");
        reader.endObject();
        reader.beginProperty(102);
        long totalRows = reader.readUnsignedLeb128();
        rejectNonEmptyList(103, "index_pointers");
        rejectNonEmptyList(104, "index_storage_infos");
        long nextRowId = reader.beginOptionalProperty(105) ? reader.readUnsignedLeb128() : totalRows;
        reader.endObject();
        payloadBoundaryReached = false;
        payloadType = null;
        if (remainingEntries == 0) reader.endObject();
        return new DuckDbTableEntryEnvelope(createInfo, new DuckDbTableEntryEnvelope.MetaPointer(packed, offset),
                totalRows, nextRowId, DuckDbTableEntryEnvelope.Boundary.TABLE_METADATA_CHAIN_ROW_GROUPS_AND_INDEXES_UNSUPPORTED);
    }

    private void rejectNonEmptyList(int fieldId, String name) {
        if (!reader.beginOptionalProperty(fieldId)) return;
        long count = reader.beginList();
        if (count != 0) throw new StorageFormatException("DuckDB table " + name + " field " + fieldId + " is unsupported when non-empty");
    }

    private OptionalLong readOptionalLastValue() {
        if (!reader.beginOptionalProperty(207)) {
            return OptionalLong.empty();
        }
        return reader.readBoolean() ? OptionalLong.of(reader.readSignedLeb128()) : OptionalLong.empty();
    }

    private void requirePendingPayload(CatalogEntryType expectedType, String name) {
        if (!payloadBoundaryReached) {
            throw new StorageFormatException("DuckDB " + name + " requires a pending catalog entry payload");
        }
        if (payloadType != expectedType) {
            throw new StorageFormatException("DuckDB pending catalog payload is " + payloadType + ", not a " + name);
        }
    }

    private void requireNonNullCreateInfo(String entryName) {
        if (!reader.readBoolean()) {
            throw new StorageFormatException("DuckDB checkpoint " + entryName + " entry has null CreateInfo");
        }
    }

    private void requireCreateInfoType(CatalogEntryType expectedType, String entryName) {
        long createInfoType = reader.readUnsignedLeb128();
        if (createInfoType != expectedType.nativeTag()) {
            throw new StorageFormatException("DuckDB checkpoint " + entryName + " CreateInfo type mismatch: expected "
                    + expectedType.nativeTag() + " but found " + Long.toUnsignedString(createInfoType));
        }
    }

    private void rejectLegacyQualificationField(int fieldId, String name, String createInfoName) {
        if (reader.beginOptionalProperty(fieldId)) {
            throw new StorageFormatException("DuckDB V2.0 " + createInfoName + " must not contain legacy " + name
                    + " field " + fieldId);
        }
    }

    private void rejectUnsupportedNonDefaultField(int fieldId, String name, String createInfoName) {
        if (reader.beginOptionalProperty(fieldId)) {
            throw new StorageFormatException("DuckDB " + createInfoName + " " + name + " field " + fieldId
                    + " is unsupported when non-default");
        }
    }

    private List<String> readQualifiedNamePath() {
        reader.beginObject();
        List<String> path = new ArrayList<>();
        if (reader.beginOptionalProperty(100)) {
            long count = reader.beginList();
            if (count < 0 || count > Integer.MAX_VALUE) {
                throw new StorageFormatException("DuckDB CreateInfo qualified name path exceeds supported bound: "
                        + Long.toUnsignedString(count));
            }
            for (int index = 0; index < count; index++) {
                path.add(reader.readString());
            }
        }
        reader.endObject();
        return List.copyOf(path);
    }

    private void finishCreateInfoEntry() {
        reader.endObject(); // CreateInfo
        reader.endObject(); // checkpoint entry
        payloadBoundaryReached = false;
        payloadType = null;
        if (remainingEntries == 0) {
            reader.endObject(); // checkpoint root
        }
    }

    public enum CatalogEntryPayloadOutcome {
        SCHEMA_CREATE_INFO_AVAILABLE,
        TABLE_CREATE_INFO_PREFIX_AVAILABLE,
        SEQUENCE_CREATE_INFO_AVAILABLE,
        UNSUPPORTED_CREATE_INFO_PAYLOAD
    }

    /** The only CatalogType variants checkpoint writer/reader dispatch in the pinned source. */
    public enum CatalogEntryType {
        TABLE(1),
        SCHEMA(2),
        VIEW(3),
        INDEX(4),
        SEQUENCE(6),
        TYPE(8),
        TRIGGER(11),
        MACRO(30),
        TABLE_MACRO(31);

        private final int nativeTag;

        CatalogEntryType(int nativeTag) {
            this.nativeTag = nativeTag;
        }

        public int nativeTag() {
            return nativeTag;
        }

        static CatalogEntryType fromNativeTag(int nativeTag) {
            for (CatalogEntryType value : values()) {
                if (value.nativeTag == nativeTag) {
                    return value;
                }
            }
            return null;
        }
    }

    public record CatalogEntryEnvelope(CatalogEntryType type, CatalogEntryPayloadOutcome payloadOutcome) {
    }
}
