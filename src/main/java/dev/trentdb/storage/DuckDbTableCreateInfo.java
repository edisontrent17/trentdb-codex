package dev.trentdb.storage;

import java.util.List;

/**
 * The decoded V2.0 CreateTableInfo prefix. The enclosing table entry remains positioned at native
 * table metadata field 101; table data pointers, rows, and indexes are intentionally not decoded.
 */
public record DuckDbTableCreateInfo(
        List<String> qualifiedNamePath,
        boolean temporary,
        boolean internal,
        DuckDbSequenceCreateInfo.OnCreateConflict onConflict,
        String sql,
        String extensionName,
        String tableName,
        List<Column> columns,
        Boundary boundary) {
    public DuckDbTableCreateInfo {
        qualifiedNamePath = List.copyOf(qualifiedNamePath);
        columns = List.copyOf(columns);
        if (onConflict == null || sql == null || extensionName == null || tableName == null || boundary == null) {
            throw new IllegalArgumentException("table CreateInfo fields must not be null");
        }
    }

    public enum Boundary {
        TABLE_METADATA_FIELD_101_UNSUPPORTED
    }

    /** A regular scalar LogicalType whose native object has no ExtraTypeInfo field 101. */
    public record Column(String name, ScalarLogicalType type, Category category, Compression compression) {
        public Column {
            if (name == null || type == null || category == null || compression == null) {
                throw new IllegalArgumentException("column fields must not be null");
            }
        }
    }

    public enum Category {
        STANDARD(0), GENERATED(1);
        private final int nativeTag;
        Category(int nativeTag) { this.nativeTag = nativeTag; }
        public int nativeTag() { return nativeTag; }
        static Category fromNativeTag(long tag) {
            for (Category value : values()) if (value.nativeTag == tag) return value;
            throw new IllegalArgumentException("unsupported TableColumnType tag: " + tag);
        }
    }

    public enum Compression {
        AUTO(0), UNCOMPRESSED(1), CONSTANT(2), RLE(3), DICTIONARY(4), PFOR_DELTA(5), BITPACKING(6),
        FSST(7), CHIMP(8), PATAS(9), ALP(10), ALPRD(11), ZSTD(12), ROARING(13), EMPTY(14), DICT_FSST(15);
        private final int nativeTag;
        Compression(int nativeTag) { this.nativeTag = nativeTag; }
        public int nativeTag() { return nativeTag; }
        static Compression fromNativeTag(long tag) {
            for (Compression value : values()) if (value.nativeTag == tag) return value;
            throw new IllegalArgumentException("unsupported CompressionType tag: " + tag);
        }
    }

    public enum ScalarLogicalType {
        BOOLEAN(10), TINYINT(11), SMALLINT(12), INTEGER(13), BIGINT(14), DATE(15), TIME(16),
        TIMESTAMP_SEC(17), TIMESTAMP_MS(18), TIMESTAMP(19), TIMESTAMP_NS(20), FLOAT(22), DOUBLE(23),
        CHAR(24), VARCHAR(25), BLOB(26), INTERVAL(27), UTINYINT(28), USMALLINT(29), UINTEGER(30),
        UBIGINT(31), TIMESTAMP_TZ(32), TIMESTAMP_TZ_NS(33), TIME_TZ(34), TIME_NS(35), BIT(36),
        BIGNUM(39), UHUGEINT(49), HUGEINT(50), UUID(54);
        private final int nativeTag;
        ScalarLogicalType(int nativeTag) { this.nativeTag = nativeTag; }
        public int nativeTag() { return nativeTag; }
        static ScalarLogicalType fromNativeTag(long tag) {
            for (ScalarLogicalType value : values()) if (value.nativeTag == tag) return value;
            throw new IllegalArgumentException("unsupported or non-scalar LogicalTypeId tag: " + tag);
        }
    }
}
