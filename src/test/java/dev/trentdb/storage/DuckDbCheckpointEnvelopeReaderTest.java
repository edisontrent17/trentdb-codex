package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuckDbCheckpointEnvelopeReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsNativeCheckpointRootAndStopsAtTableCreateInfoPayloadBoundary() {
        // { field 100: list(1) [ { field 99: CatalogType::TABLE_ENTRY, field 100: <CreateInfo> } ] }
        byte[] payload = {100, 0, 1, 99, 0, 1, 100, 0};
        try (SingleFileBlockManager manager = metadataFile("table-envelope.duckdb", payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            assertEquals(1, reader.beginCheckpoint());
            assertEquals(new DuckDbCheckpointEnvelopeReader.CatalogEntryEnvelope(
                    DuckDbCheckpointEnvelopeReader.CatalogEntryType.TABLE,
                    DuckDbCheckpointEnvelopeReader.CatalogEntryPayloadOutcome.TABLE_CREATE_INFO_PREFIX_AVAILABLE),
                    reader.readNextEntryEnvelope());
            StorageFormatException failure = assertThrows(StorageFormatException.class, reader::readNextEntryEnvelope);
            assertEquals("DuckDB catalog entry payload is unsupported; cannot advance envelope reader", failure.getMessage());
        }
    }

    @Test
    void readsSchemaCreateInfoInPinnedNativeFieldOrder() {
        // One schema entry. The CreateInfo pointer is present, and its fields are emitted in the
        // order from serialize_create_info.cpp: type, temporary, on_conflict, sql, extension,
        // qualified_name. QualifiedName itself is object(field 100: identifier vector).
        byte[] payload = {
                100, 0, 1,
                99, 0, 2, 100, 0, 1,
                100, 0, 2,
                103, 0, 1,
                105, 0, 1,
                106, 0, 23, 'C', 'R', 'E', 'A', 'T', 'E', ' ', 'S', 'C', 'H', 'E', 'M', 'A', ' ', 'a', 'n', 'a', 'l', 'y', 't', 'i', 'c', 's',
                110, 0, 4, 'c', 'o', 'r', 'e',
                111, 0, 100, 0, 3, 6, 'm', 'e', 'm', 'o', 'r', 'y', 9, 'a', 'n', 'a', 'l', 'y', 't', 'i', 'c', 's', 0,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff
        };
        try (SingleFileBlockManager manager = metadataFile("schema-create-info.duckdb", payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            assertEquals(1, reader.beginCheckpoint());
            DuckDbCheckpointEnvelopeReader.CatalogEntryEnvelope envelope = reader.readNextEntryEnvelope();
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SCHEMA, envelope.type());
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryPayloadOutcome.SCHEMA_CREATE_INFO_AVAILABLE,
                    envelope.payloadOutcome());
            assertEquals(new DuckDbSchemaCreateInfo(List.of("memory", "analytics", ""), true, false,
                    DuckDbSchemaCreateInfo.OnCreateConflict.IGNORE_ON_CONFLICT,
                    "CREATE SCHEMA analytics", "core"), reader.readSchemaCreateInfo());
            assertThrows(StorageFormatException.class, reader::readNextEntryEnvelope);
        }
    }

    @Test
    void appliesNativeOptionalDefaultsForSchemaCreateInfo() {
        // Native deserialization treats all optional CreateInfo fields and qualified_name as
        // defaults when absent. V2 native writes 111, but accepting the generated reader's
        // explicit default keeps this decoder's field semantics identical.
        byte[] payload = {
                100, 0, 1,
                99, 0, 2, 100, 0, 1,
                100, 0, 2, 105, 0, 0,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff
        };
        try (SingleFileBlockManager manager = metadataFile("schema-defaults.duckdb", payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            reader.readNextEntryEnvelope();
            assertEquals(new DuckDbSchemaCreateInfo(List.of(), false, false,
                    DuckDbSchemaCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", ""),
                    reader.readSchemaCreateInfo());
        }
    }

    @Test
    void rejectsCorruptSchemaPointerTypeAndUnsupportedFields() {
        assertSchemaFailure("null-schema.duckdb", new byte[] {
                100, 0, 1, 99, 0, 2, 100, 0, 0
        }, "DuckDB checkpoint schema entry has null CreateInfo");
        assertSchemaFailure("wrong-schema-type.duckdb", new byte[] {
                100, 0, 1, 99, 0, 2, 100, 0, 1, 100, 0, 1
        }, "DuckDB checkpoint schema CreateInfo type mismatch: expected 2 but found 1");
        assertSchemaFailure("legacy-schema-qualification.duckdb", new byte[] {
                100, 0, 1, 99, 0, 2, 100, 0, 1, 100, 0, 2, 101, 0
        }, "DuckDB V2.0 SchemaCreateInfo must not contain legacy catalog field 101");
        assertSchemaFailure("schema-value-comment.duckdb", new byte[] {
                100, 0, 1, 99, 0, 2, 100, 0, 1, 100, 0, 2, 105, 0, 0, 107, 0
        }, "DuckDB SchemaCreateInfo comment (Value) field 107 is unsupported when non-default");
    }

    @Test
    void validatesRootOrderCatalogTypeTagAndCreateInfoField() {
        try (SingleFileBlockManager manager = metadataFile("empty-checkpoint.duckdb",
                new byte[] {100, 0, 0, (byte) 0xff, (byte) 0xff})) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            assertEquals(0, reader.beginCheckpoint());
            assertThrows(StorageFormatException.class, reader::readNextEntryEnvelope);
        }

        try (SingleFileBlockManager manager = metadataFile("wrong-type.duckdb",
                new byte[] {100, 0, 1, 99, 0, 9, 100, 0})) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            StorageFormatException failure = assertThrows(StorageFormatException.class, reader::readNextEntryEnvelope);
            assertEquals("DuckDB checkpoint contains an unrecognized catalog type tag: 9", failure.getMessage());
        }

        try (SingleFileBlockManager manager = metadataFile("wrong-payload-field.duckdb",
                new byte[] {100, 0, 1, 99, 0, 2, 101, 0})) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            StorageFormatException failure = assertThrows(StorageFormatException.class, reader::readNextEntryEnvelope);
            assertEquals("DuckDB binary metadata field mismatch: expected 100 but found 101", failure.getMessage());
        }
    }

    private void assertSchemaFailure(String name, byte[] payload, String message) {
        try (SingleFileBlockManager manager = metadataFile(name, payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager,
                    MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            reader.readNextEntryEnvelope();
            StorageFormatException failure = assertThrows(StorageFormatException.class, reader::readSchemaCreateInfo);
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
