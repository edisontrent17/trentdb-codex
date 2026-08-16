package dev.trentdb.storage;

import dev.trentdb.storage.format.StorageFormatException;

import java.util.List;
import java.util.OptionalLong;

/** The V2.0 {@code CreateSequenceInfo} payload carried by a checkpoint sequence entry. */
public record DuckDbSequenceCreateInfo(
        List<String> qualifiedNamePath,
        boolean temporary,
        boolean internal,
        OnCreateConflict onConflict,
        String sql,
        String extensionName,
        String sequenceName,
        long usageCount,
        long increment,
        long minValue,
        long maxValue,
        long startValue,
        boolean cycle,
        OptionalLong lastValue) {

    public DuckDbSequenceCreateInfo {
        qualifiedNamePath = List.copyOf(qualifiedNamePath);
        if (onConflict == null) {
            throw new IllegalArgumentException("onConflict must not be null");
        }
        if (sql == null || extensionName == null || sequenceName == null || lastValue == null) {
            throw new IllegalArgumentException("Schema-independent sequence fields must not be null");
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
            throw new StorageFormatException("DuckDB SequenceCreateInfo contains an unrecognized "
                    + "OnCreateConflict tag: " + Long.toUnsignedString(nativeTag));
        }
    }
}
