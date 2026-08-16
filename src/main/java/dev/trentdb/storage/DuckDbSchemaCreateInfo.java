package dev.trentdb.storage;

import dev.trentdb.storage.format.StorageFormatException;

import java.util.List;

/**
 * The V2.0 {@code CreateSchemaInfo} payload carried by a checkpoint schema entry.
 *
 * <p>This model covers the base {@code CreateInfo} fields that can be decoded without
 * interpreting a {@code Value}, tag map, or dependency list. Those three optional fields are
 * accepted only when absent (their native default); a non-default value is deliberately rejected
 * rather than skipped because BinaryDeserializer fields have no encoded payload length.</p>
 */
public record DuckDbSchemaCreateInfo(
        List<String> qualifiedNamePath,
        boolean temporary,
        boolean internal,
        OnCreateConflict onConflict,
        String sql,
        String extensionName) {

    public DuckDbSchemaCreateInfo {
        qualifiedNamePath = List.copyOf(qualifiedNamePath);
        if (onConflict == null) {
            throw new IllegalArgumentException("onConflict must not be null");
        }
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        if (extensionName == null) {
            throw new IllegalArgumentException("extensionName must not be null");
        }
    }

    /** Native {@code OnCreateConflict:uint8} values used by the pinned V2.0 source. */
    public enum OnCreateConflict {
        ERROR_ON_CONFLICT(0),
        IGNORE_ON_CONFLICT(1),
        REPLACE_ON_CONFLICT(2),
        ALTER_ON_CONFLICT(3);

        private final int nativeTag;

        OnCreateConflict(int nativeTag) {
            this.nativeTag = nativeTag;
        }

        public int nativeTag() {
            return nativeTag;
        }

        static OnCreateConflict fromNativeTag(long nativeTag) {
            for (OnCreateConflict value : values()) {
                if (value.nativeTag == nativeTag) {
                    return value;
                }
            }
            throw new StorageFormatException("DuckDB SchemaCreateInfo contains an unrecognized "
                    + "OnCreateConflict tag: " + Long.toUnsignedString(nativeTag));
        }
    }
}
