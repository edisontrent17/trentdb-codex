package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuckDbCheckpointMetadataWriterTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsMixedSchemaSequenceAndPrimitiveTableCatalogEntries() {
        Path path = directory.resolve("mixed.duckdb");
        DuckDbSchemaCreateInfo schema = new DuckDbSchemaCreateInfo(List.of("memory", "analytics"), true, false,
                DuckDbSchemaCreateInfo.OnCreateConflict.IGNORE_ON_CONFLICT, "create schema analytics", "core");
        DuckDbSequenceCreateInfo sequence = new DuckDbSequenceCreateInfo(List.of("memory", "analytics", "seq"), false,
                true, DuckDbSequenceCreateInfo.OnCreateConflict.REPLACE_ON_CONFLICT, "", "", "seq", 4, -2, -9, 99,
                7, true, OptionalLong.of(11));
        DuckDbTableCreateInfo tableInfo = new DuckDbTableCreateInfo(List.of("memory", "analytics", "events"), false,
                false, DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "events",
                List.of(new DuckDbTableCreateInfo.Column("flag", DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN,
                                DuckDbTableCreateInfo.Category.STANDARD, DuckDbTableCreateInfo.Compression.UNCOMPRESSED),
                        new DuckDbTableCreateInfo.Column("id", DuckDbTableCreateInfo.ScalarLogicalType.BIGINT,
                                DuckDbTableCreateInfo.Category.STANDARD, DuckDbTableCreateInfo.Compression.AUTO)),
                DuckDbTableCreateInfo.Boundary.TABLE_METADATA_FIELD_101_UNSUPPORTED);
        DuckDbTableEntryEnvelope table = new DuckDbTableEntryEnvelope(tableInfo,
                new DuckDbTableEntryEnvelope.MetaPointer(0x0200_0000_0000_0003L, 17), 9, 12,
                DuckDbTableEntryEnvelope.Boundary.TABLE_METADATA_CHAIN_ROW_GROUPS_AND_INDEXES_UNSUPPORTED);
        MetaBlockPointer root;
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16]);
             DuckDbCheckpointMetadataWriter writer = new DuckDbCheckpointMetadataWriter(manager)) {
            root = writer.writeCheckpoint(List.of(new DuckDbCheckpointMetadataWriter.Schema(schema),
                    new DuckDbCheckpointMetadataWriter.Sequence(sequence),
                    new DuckDbCheckpointMetadataWriter.Table(table)));
            assertEquals(0, root.blockId());
            assertEquals(8, root.offset());
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager, root);
            assertEquals(3, reader.beginCheckpoint());
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SCHEMA, reader.readNextEntryEnvelope().type());
            assertEquals(schema, reader.readSchemaCreateInfo());
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SEQUENCE, reader.readNextEntryEnvelope().type());
            assertEquals(sequence, reader.readSequenceCreateInfo());
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryType.TABLE, reader.readNextEntryEnvelope().type());
            assertEquals(table, reader.readTableEntryEnvelope());
        }
    }

    @Test
    void crossesMetadataSubBlocksWithCatalogListAndPreservesDefaults() {
        Path path = directory.resolve("many.duckdb");
        List<DuckDbCheckpointMetadataWriter.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 220; index++) {
            entries.add(new DuckDbCheckpointMetadataWriter.Schema(new DuckDbSchemaCreateInfo(
                    List.of("memory", "s" + index), false, false,
                    DuckDbSchemaCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "")));
        }
        MetaBlockPointer root;
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16]);
             DuckDbCheckpointMetadataWriter writer = new DuckDbCheckpointMetadataWriter(manager)) {
            root = writer.writeCheckpoint(entries);
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager, root);
            assertEquals(220, reader.beginCheckpoint());
            for (int index = 0; index < 220; index++) {
                assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryType.SCHEMA, reader.readNextEntryEnvelope().type());
                assertEquals(List.of("memory", "s" + index), reader.readSchemaCreateInfo().qualifiedNamePath());
            }
        }
    }

    @Test
    void rejectsUnsupportedPrimitiveTableShapesBeforePublication() {
        Path path = directory.resolve("rejected.duckdb");
        DuckDbTableCreateInfo badType = tableWith(new DuckDbTableCreateInfo.Column("text",
                DuckDbTableCreateInfo.ScalarLogicalType.VARCHAR, DuckDbTableCreateInfo.Category.STANDARD,
                DuckDbTableCreateInfo.Compression.AUTO));
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16]);
             DuckDbCheckpointMetadataWriter writer = new DuckDbCheckpointMetadataWriter(manager)) {
            assertThrows(StorageFormatException.class, () -> writer.writeCheckpoint(List.of(
                    new DuckDbCheckpointMetadataWriter.Table(envelope(badType)))));
        }
        DuckDbTableCreateInfo generated = tableWith(new DuckDbTableCreateInfo.Column("id",
                DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, DuckDbTableCreateInfo.Category.GENERATED,
                DuckDbTableCreateInfo.Compression.AUTO));
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(directory.resolve("generated.duckdb"), new byte[16]);
             DuckDbCheckpointMetadataWriter writer = new DuckDbCheckpointMetadataWriter(manager)) {
            assertThrows(StorageFormatException.class, () -> writer.writeCheckpoint(List.of(
                    new DuckDbCheckpointMetadataWriter.Table(envelope(generated)))));
        }
    }

    private static DuckDbTableCreateInfo tableWith(DuckDbTableCreateInfo.Column column) {
        return new DuckDbTableCreateInfo(List.of("memory", "main", "t"), false, false,
                DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "t", List.of(column),
                DuckDbTableCreateInfo.Boundary.TABLE_METADATA_FIELD_101_UNSUPPORTED);
    }

    private static DuckDbTableEntryEnvelope envelope(DuckDbTableCreateInfo info) {
        return new DuckDbTableEntryEnvelope(info, new DuckDbTableEntryEnvelope.MetaPointer(0, 0), 0, 0,
                DuckDbTableEntryEnvelope.Boundary.TABLE_METADATA_CHAIN_ROW_GROUPS_AND_INDEXES_UNSUPPORTED);
    }
}
