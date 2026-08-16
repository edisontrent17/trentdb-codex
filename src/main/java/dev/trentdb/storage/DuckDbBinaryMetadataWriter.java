package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact V2 BinarySerializer primitive framing over a {@link MetadataChainWriter}. */
public final class DuckDbBinaryMetadataWriter {
    public static final int MESSAGE_TERMINATOR_FIELD_ID = DuckDbBinaryMetadataReader.MESSAGE_TERMINATOR_FIELD_ID;
    private final MetadataChainWriter chain;

    public DuckDbBinaryMetadataWriter(MetadataChainWriter chain) {
        this.chain = Objects.requireNonNull(chain, "chain");
    }

    public MetaBlockPointer currentPointer() {
        return chain.currentPointer();
    }

    public void beginObject() {
        // DuckDB BinarySerializer objects have no opening marker.
    }

    public void endObject() {
        writeFieldId(MESSAGE_TERMINATOR_FIELD_ID);
    }

    public void beginProperty(int fieldId) {
        if (fieldId < 0 || fieldId >= MESSAGE_TERMINATOR_FIELD_ID) {
            throw new IllegalArgumentException("DuckDB binary metadata property ID is outside [0, 65535): " + fieldId);
        }
        writeFieldId(fieldId);
    }

    public void writeBoolean(boolean value) {
        chain.writeByte(value ? 1 : 0);
    }

    public void writeUnsignedLeb128(long value) {
        do {
            int next = (int) (value & 0x7f);
            value >>>= 7;
            chain.writeByte(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    public void writeSignedLeb128(long value) {
        boolean more;
        do {
            int next = (int) value & 0x7f;
            value >>= 7;
            more = !((value == 0 && (next & 0x40) == 0) || (value == -1 && (next & 0x40) != 0));
            chain.writeByte(more ? next | 0x80 : next);
        } while (more);
    }

    public void writeUnsignedLongLittleEndian(long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            chain.writeByte((int) (value >>> (Byte.SIZE * index)));
        }
    }
    public void writeString(String value) {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        writeUnsignedLeb128(bytes.length);
        chain.write(bytes);
    }


    private void writeFieldId(int fieldId) {
        chain.writeByte(fieldId);
        chain.writeByte(fieldId >>> Byte.SIZE);
    }
}
