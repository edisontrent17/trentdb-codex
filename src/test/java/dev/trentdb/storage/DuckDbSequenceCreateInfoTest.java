package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuckDbSequenceCreateInfoTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsSequenceCreateInfoInPinnedNativeFieldOrder() {
        // V2 root -> entry envelope -> non-null CreateSequenceInfo. Common CreateInfo fields
        // precede sequence fields 200-207, exactly as serialize_create_info.cpp writes them.
        byte[] payload = {
                100, 0, 1,
                99, 0, 6, 100, 0, 1,
                100, 0, 6,
                103, 0, 1,
                105, 0, 2,
                106, 0, 7, 's', 'e', 'q', ' ', 's', 'q', 'l',
                110, 0, 3, 'e', 'x', 't',
                111, 0, 100, 0, 3,
                6, 'm', 'e', 'm', 'o', 'r', 'y',
                4, 'm', 'a', 'i', 'n',
                8, 'o', 'r', 'd', 'e', 'r', '_', 'i', 'd',
                (byte) 0xff, (byte) 0xff,
                (byte) 200, 0, 8, 'o', 'r', 'd', 'e', 'r', '_', 'i', 'd',
                (byte) 201, 0, 42,
                (byte) 202, 0, 0x7e,
                (byte) 204, 0, (byte) 0xe3, 0,
                (byte) 205, 0, 10,
                (byte) 206, 0, 1,
                (byte) 207, 0, 1, 0x7c,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff
        };
        try (SingleFileBlockManager manager = metadataFile("sequence-create-info.duckdb", payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            assertEquals(1, reader.beginCheckpoint());
            DuckDbCheckpointEnvelopeReader.CatalogEntryEnvelope envelope = reader.readNextEntryEnvelope();
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SEQUENCE, envelope.type());
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryPayloadOutcome.SEQUENCE_CREATE_INFO_AVAILABLE,
                    envelope.payloadOutcome());
            assertEquals(new DuckDbSequenceCreateInfo(List.of("memory", "main", "order_id"), true, false,
                    DuckDbSequenceCreateInfo.OnCreateConflict.REPLACE_ON_CONFLICT, "seq sql", "ext", "order_id",
                    42, -2, 1, 99, 10, true, OptionalLong.of(-4)), reader.readSequenceCreateInfo());
        }
    }

    @Test
    void appliesPinnedSequenceConstructorDefaultsAndNullableLastValueDefault() {
        // Every sequence field is WritePropertyWithDefault. The common CreateInfo reader also
        // treats qualified_name as its explicit default when absent.
        byte[] payload = {
                100, 0, 1,
                99, 0, 6, 100, 0, 1,
                100, 0, 6, 105, 0, 0,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff
        };
        try (SingleFileBlockManager manager = metadataFile("sequence-defaults.duckdb", payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            reader.readNextEntryEnvelope();
            assertEquals(new DuckDbSequenceCreateInfo(List.of(), false, false,
                    DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "", 0,
                    1, 1, Long.MAX_VALUE, 1, false, OptionalLong.empty()), reader.readSequenceCreateInfo());
        }
    }

    @Test
    void rejectsTypeMismatchAndTruncatedSequenceObject() {
        assertFailure("wrong-sequence-type.duckdb", new byte[] {
                100, 0, 1, 99, 0, 6, 100, 0, 1, 100, 0, 2
        }, "DuckDB checkpoint sequence CreateInfo type mismatch: expected 6 but found 2");

        // The CreateInfo object ends before its required object terminator. Padding after the
        // fixture is not accepted as a terminator, so the reader fails rather than treating the
        // truncated sequence as valid.
        assertFailure("truncated-sequence.duckdb", new byte[] {
                100, 0, 1, 99, 0, 6, 100, 0, 1, 100, 0, 6, 105, 0, 0
        }, "DuckDB binary metadata object expected terminator 65535 but found field 0");
    }

    @Test
    void rejectsUnsupportedSharedCreateInfoPayloadBeforeSequenceFields() {
        assertFailure("sequence-tags.duckdb", new byte[] {
                100, 0, 1, 99, 0, 6, 100, 0, 1, 100, 0, 6, 105, 0, 0, 108, 0
        }, "DuckDB SequenceCreateInfo tags (InsertionOrderPreservingMap<string>) field 108 is unsupported when non-default");
    }

    private void assertFailure(String name, byte[] payload, String message) {
        try (SingleFileBlockManager manager = metadataFile(name, payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            reader.readNextEntryEnvelope();
            StorageFormatException failure = assertThrows(StorageFormatException.class, reader::readSequenceCreateInfo);
            assertEquals(message, failure.getMessage());
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

    private static void putLongLittleEndian(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }
}
