package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbBinaryMetadataReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsGoldenNestedPropertyAndListFramingInDuckDbSerializationOrder() {
        // Root: field 100 -> list(2) -> objects { field 1: signed LEB128, field 2: string } -> terminator.
        byte[] payload = {
                100, 0, 2,
                1, 0, 0x7e,
                2, 0, 3, 'c', 'a', 't', (byte) 0xff, (byte) 0xff,
                1, 0, 0x01,
                2, 0, 3, 'd', 'o', 'g', (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff
        };
        try (SingleFileBlockManager manager = metadataFile("golden-framing.duckdb", payload)) {
            DuckDbBinaryMetadataReader reader = reader(manager);
            reader.beginObject();
            reader.beginProperty(100);
            assertEquals(2, reader.beginList());

            reader.beginObject();
            reader.beginProperty(1);
            assertEquals(-2, reader.readSignedLeb128());
            reader.beginProperty(2);
            assertEquals("cat", reader.readString());
            reader.endObject();

            reader.beginObject();
            reader.beginProperty(1);
            assertEquals(1, reader.readSignedLeb128());
            reader.beginProperty(2);
            assertEquals("dog", reader.readString());
            reader.endObject();
            reader.endObject();
        }
    }

    @Test
    void optionalLookaheadPreservesDuckDbBehaviorAndCannotSkipUnknownFields() {
        byte[] payload = {4, 0, 1, (byte) 0xff, (byte) 0xff};
        try (SingleFileBlockManager manager = metadataFile("optional-framing.duckdb", payload)) {
            DuckDbBinaryMetadataReader reader = reader(manager);
            reader.beginObject();
            assertFalse(reader.beginOptionalProperty(3));
            assertTrue(reader.beginOptionalProperty(4));
            assertTrue(reader.readBoolean());
            reader.endObject();
        }

        byte[] unknown = {5, 0, 0, (byte) 0xff, (byte) 0xff};
        try (SingleFileBlockManager manager = metadataFile("unknown-framing.duckdb", unknown)) {
            DuckDbBinaryMetadataReader reader = reader(manager);
            reader.beginObject();
            assertFalse(reader.beginOptionalProperty(3));
            StorageFormatException failure = assertThrows(StorageFormatException.class, reader::endObject);
            assertEquals("DuckDB binary metadata object expected terminator 65535 but found field 5", failure.getMessage());
        }
    }

    @Test
    void rejectsFieldTerminatorAndLeb128Corruption() {
        try (SingleFileBlockManager manager = metadataFile("field-corrupt.duckdb", new byte[] {2, 0, (byte) 0xff, (byte) 0xff})) {
            DuckDbBinaryMetadataReader reader = reader(manager);
            reader.beginObject();
            StorageFormatException failure = assertThrows(StorageFormatException.class, () -> reader.beginProperty(1));
            assertEquals("DuckDB binary metadata field mismatch: expected 1 but found 2", failure.getMessage());
        }

        byte[] oversizedLeb128 = new byte[12];
        Arrays.fill(oversizedLeb128, (byte) 0x80);
        oversizedLeb128[10] = 0;
        try (SingleFileBlockManager manager = metadataFile("leb-corrupt.duckdb", oversizedLeb128)) {
            DuckDbBinaryMetadataReader reader = reader(manager);
            assertThrows(StorageFormatException.class, reader::readUnsignedLeb128);
        }

        try (SingleFileBlockManager manager = metadataFile("blob-corrupt.duckdb", new byte[] {3, 1, 2, 3})) {
            DuckDbBinaryMetadataReader reader = reader(manager);
            StorageFormatException failure = assertThrows(StorageFormatException.class, () -> reader.readBlob(2));
            assertEquals("DuckDB binary metadata blob length mismatch: expected 2 but found 3", failure.getMessage());
        }
    }

    private SingleFileBlockManager metadataFile(String name, byte[] payload) {
        Path path = temporaryDirectory.resolve(name);
        try (SingleFileBlockManager writer = SingleFileBlockManager.create(path, new byte[16])) {
            byte[] block = new byte[writer.usableBlockSize()];
            putLongLittleEndian(block, 0, MetaBlockPointer.INVALID_BLOCK_POINTER);
            System.arraycopy(payload, 0, block, Long.BYTES, payload.length);
            writer.writeBlock(0, block);
        }
        return SingleFileBlockManager.openMetadataReadOnly(path);
    }

    private static DuckDbBinaryMetadataReader reader(SingleFileBlockManager manager) {
        return new DuckDbBinaryMetadataReader(new MetadataChainReader(manager, MetaBlockPointer.of(0, 0, 0)),
                StorageFormat.STORAGE_VERSION);
    }

    private static void putLongLittleEndian(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }
}
