package dev.trentdb.storage;

import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;

import java.nio.charset.StandardCharsets;

/**
 * V2.0 framing reader for DuckDB's BinaryDeserializer over a metadata block chain.
 *
 * <p>Objects have no opening marker, properties start with a little-endian {@code uint16} field
 * id, and objects end with {@value #MESSAGE_TERMINATOR_FIELD_ID}. Lists and integer primitives
 * use DuckDB's LEB128 encoding. This reader intentionally exposes framing only: a field has no
 * encoded byte length, so an unknown field cannot be skipped safely without its schema. That is
 * also why DuckDB's native BinaryDeserializer reads known fields in schema order.</p>
 */
public final class DuckDbBinaryMetadataReader {
    public static final int MESSAGE_TERMINATOR_FIELD_ID = 0xffff;
    private static final int MAX_NESTING = 1_024;

    private final MetadataChainReader stream;
    private final long storageVersion;
    private int nesting;
    private Integer bufferedField;

    public DuckDbBinaryMetadataReader(MetadataChainReader stream, long storageVersion) {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        if (storageVersion != StorageFormat.STORAGE_VERSION) {
            throw new StorageFormatException("DuckDB binary metadata framing supports storage format "
                    + StorageFormat.STORAGE_VERSION + " only, not " + storageVersion);
        }
        this.stream = stream;
        this.storageVersion = storageVersion;
    }

    public long storageVersion() {
        return storageVersion;
    }

    /** Mirrors BinaryDeserializer::OnObjectBegin; it consumes no bytes. */
    public void beginObject() {
        if (nesting >= MAX_NESTING) {
            throw new StorageFormatException("DuckDB binary metadata nesting exceeds " + MAX_NESTING);
        }
        nesting++;
    }

    /** Mirrors BinaryDeserializer::OnObjectEnd by requiring the {@code uint16} terminator. */
    public void endObject() {
        requireObject();
        int field = nextFieldId();
        if (field != MESSAGE_TERMINATOR_FIELD_ID) {
            throw new StorageFormatException("DuckDB binary metadata object expected terminator "
                    + MESSAGE_TERMINATOR_FIELD_ID + " but found field " + field);
        }
        nesting--;
    }

    /** Reads and verifies a required property ID before the caller reads its payload. */
    public void beginProperty(int expectedFieldId) {
        validatePropertyId(expectedFieldId);
        requireObject();
        int actual = nextFieldId();
        if (actual != expectedFieldId) {
            throw new StorageFormatException("DuckDB binary metadata field mismatch: expected " + expectedFieldId
                    + " but found " + actual);
        }
    }

    /**
     * Mirrors BinaryDeserializer's one-field optional lookahead. A false result leaves the next
     * field buffered; it does not skip unknown forward fields because their payload has no length.
     */
    public boolean beginOptionalProperty(int expectedFieldId) {
        validatePropertyId(expectedFieldId);
        requireObject();
        if (peekFieldId() != expectedFieldId) {
            return false;
        }
        nextFieldId();
        return true;
    }

    public long beginList() {
        return readUnsignedLeb128();
    }

    public boolean readBoolean() {
        return readUnsignedByte() != 0;
    }

    public long readUnsignedLeb128() {
        long value = 0;
        int shift = 0;
        for (int index = 0; index < 10; index++) {
            int next = readUnsignedByte();
            int payload = next & 0x7f;
            if (shift == 63 && payload > 1) {
                throw new StorageFormatException("DuckDB unsigned LEB128 integer overflows uint64");
            }
            value |= (long) payload << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new StorageFormatException("DuckDB unsigned LEB128 integer exceeds 10 bytes");
    }

    public long readSignedLeb128() {
        long value = 0;
        int shift = 0;
        int next = 0;
        for (int index = 0; index < 10; index++) {
            next = readUnsignedByte();
            int payload = next & 0x7f;
            if (shift == 63 && payload != 0 && payload != 0x7f && payload != 1) {
                throw new StorageFormatException("DuckDB signed LEB128 integer overflows int64");
            }
            value |= (long) payload << shift;
            shift += 7;
            if ((next & 0x80) == 0) {
                if (shift < Long.SIZE && (next & 0x40) != 0) {
                    value |= -1L << shift;
                }
                return value;
            }
        }
        throw new StorageFormatException("DuckDB signed LEB128 integer exceeds 10 bytes");
    }

    public String readString() {
        long length = readUnsignedLeb128();
        if (Long.compareUnsigned(length, Integer.MAX_VALUE) > 0) {
            throw new StorageFormatException("DuckDB binary metadata string exceeds Java array limit: "
                    + Long.toUnsignedString(length));
        }
        return new String(stream.readFully((int) length), StandardCharsets.UTF_8);
    }

    public byte[] readBlob(int expectedLength) {
        if (expectedLength < 0) {
            throw new IllegalArgumentException("expectedLength must not be negative");
        }
        long encodedLength = readUnsignedLeb128();
        if (encodedLength != expectedLength) {
            throw new StorageFormatException("DuckDB binary metadata blob length mismatch: expected "
                    + expectedLength + " but found " + Long.toUnsignedString(encodedLength));
        }
        return stream.readFully(expectedLength);
    }

    public byte[] readFixed(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        return stream.readFully(length);
    }
    public long readUnsignedLongLittleEndian() { byte[] b = readFixed(8); long v = 0; for (int i = 0; i < 8; i++) v |= (long) Byte.toUnsignedInt(b[i]) << (i * 8); return v; }

    private int peekFieldId() {
        if (bufferedField == null) {
            bufferedField = readFieldId();
        }
        return bufferedField;
    }

    private int nextFieldId() {
        if (bufferedField != null) {
            int result = bufferedField;
            bufferedField = null;
            return result;
        }
        return readFieldId();
    }

    private int readFieldId() {
        byte[] bytes = stream.readFully(Short.BYTES);
        return Byte.toUnsignedInt(bytes[0]) | Byte.toUnsignedInt(bytes[1]) << 8;
    }

    private int readUnsignedByte() {
        return Byte.toUnsignedInt(stream.readFully(1)[0]);
    }

    private void requireObject() {
        if (nesting == 0) {
            throw new StorageFormatException("DuckDB binary metadata property is outside an object");
        }
    }

    private static void validatePropertyId(int fieldId) {
        if (fieldId < 0 || fieldId >= MESSAGE_TERMINATOR_FIELD_ID) {
            throw new IllegalArgumentException("DuckDB binary metadata property ID is outside [0, "
                    + MESSAGE_TERMINATOR_FIELD_ID + "): " + fieldId);
        }
    }
}
