package dev.trentdb.storage.format;

/** DuckDB's on-disk checksum from {@code duckdb/common/checksum.cpp}. */
public final class DuckDbChecksum {
    private static final long WORD_MULTIPLIER = 0xbf58476d1ce4e5b9L;
    private static final long REMAINDER_MULTIPLIER = 0xc6a4a7935bd1e995L;
    private static final long REMAINDER_SEED = 0xe17a1465L;

    private DuckDbChecksum() {
    }

    public static long checksum(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("Checksum range is outside the supplied byte array");
        }
        long result = 5381L;
        int words = length / Long.BYTES;
        for (int index = 0; index < words; index++) {
            result ^= littleEndianLong(bytes, offset + index * Long.BYTES) * WORD_MULTIPLIER;
        }
        int remainder = length & 7;
        if (remainder != 0) {
            long hash = REMAINDER_SEED ^ ((long) remainder * REMAINDER_MULTIPLIER);
            int remainderOffset = offset + words * Long.BYTES;
            for (int index = 0; index < remainder; index++) {
                hash ^= (long) Byte.toUnsignedInt(bytes[remainderOffset + index]) << (index * 8);
            }
            hash *= REMAINDER_MULTIPLIER;
            hash ^= hash >>> 47;
            hash *= REMAINDER_MULTIPLIER;
            result ^= hash ^ (hash >>> 47);
        }
        return result;
    }

    public static long littleEndianLong(byte[] bytes, int offset) {
        return (long) Byte.toUnsignedInt(bytes[offset])
                | (long) Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | (long) Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | (long) Byte.toUnsignedInt(bytes[offset + 3]) << 24
                | (long) Byte.toUnsignedInt(bytes[offset + 4]) << 32
                | (long) Byte.toUnsignedInt(bytes[offset + 5]) << 40
                | (long) Byte.toUnsignedInt(bytes[offset + 6]) << 48
                | (long) Byte.toUnsignedInt(bytes[offset + 7]) << 56;
    }

    public static void putLittleEndianLong(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }
}
