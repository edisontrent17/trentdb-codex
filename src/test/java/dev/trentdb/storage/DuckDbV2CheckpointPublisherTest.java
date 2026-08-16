package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuckDbV2CheckpointPublisherTest {
    @TempDir
    Path directory;

    @Test
    void publishesAndReopensPrimitiveCatalogTableAndVector() {
        DuckDbTableCreateInfo table = new DuckDbTableCreateInfo(List.of("memory", "main", "numbers"), false, false,
                DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "numbers",
                List.of(new DuckDbTableCreateInfo.Column("n", DuckDbTableCreateInfo.ScalarLogicalType.INTEGER,
                        DuckDbTableCreateInfo.Category.STANDARD, DuckDbTableCreateInfo.Compression.UNCOMPRESSED)),
                DuckDbTableCreateInfo.Boundary.TABLE_METADATA_FIELD_101_UNSUPPORTED);
        DuckDbSchemaCreateInfo schema = new DuckDbSchemaCreateInfo(List.of("memory", "main"), false, false,
                DuckDbSchemaCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "");
        DuckDbSequenceCreateInfo sequence = new DuckDbSequenceCreateInfo(List.of("memory", "main", "s"), false, false,
                DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "s", 0, 1, 1,
                Long.MAX_VALUE, 1, false, OptionalLong.empty());
        Path path = directory.resolve("published.duckdb");
        DuckDbV2CheckpointPublisher.Publication publication = DuckDbV2CheckpointPublisher.create(path, new byte[16],
                new DuckDbV2CheckpointPublisher.Checkpoint(List.of(schema), List.of(sequence),
                        List.of(new DuckDbV2CheckpointPublisher.PrimitiveTable(table, List.of(Arrays.asList(2L, null, 9L))))));
        assertEquals(5, publication.iteration());
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            assertEquals(publication.root().blockPointer(), manager.activeHeader().metaBlock());
            assertEquals(StorageFormat.INVALID_BLOCK, manager.activeHeader().freeList());
            DuckDbCheckpointEnvelopeReader catalog = DuckDbCheckpointEnvelopeReader.openActiveCheckpoint(manager);
            assertEquals(3, catalog.beginCheckpoint());
            catalog.readNextEntryEnvelope();
            assertEquals(schema, catalog.readSchemaCreateInfo());
            catalog.readNextEntryEnvelope();
            assertEquals(sequence, catalog.readSequenceCreateInfo());
            catalog.readNextEntryEnvelope();
            DuckDbTableEntryEnvelope entry = catalog.readTableEntryEnvelope();
            assertEquals(3, entry.totalRows());
            DuckDbRowGroupHeaders groups = new DuckDbRowGroupHeaderReader(manager,
                    new MetaBlockPointer(entry.tablePointer().packedBlockPointer(), (int) entry.tablePointer().offset()),
                    List.of(DuckDbTableCreateInfo.ScalarLogicalType.INTEGER)).read();
            DuckDbPrimitiveColumnMetadata column = new DuckDbPrimitiveColumnMetadataReader(manager,
                    groups.groups().getFirst().dataPointers().getFirst(),
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER).read();
            assertEquals(Arrays.asList(2L, null, 9L), new DuckDbPrimitivePayloadReader(manager).read(
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, column.dataSegments().getFirst(),
                    column.validitySegments().getFirst()));
        }
    }

    @Test
    void invalidPublicationRetainsOldAlternatingHeader() {
        Path path = directory.resolve("retain.duckdb");
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16])) {
            assertThrows(StorageFormatException.class, () -> manager.publishCheckpoint(MetaBlockPointer.invalid()));
            assertEquals(0, manager.activeHeader().iteration());
            assertEquals(StorageFormat.INVALID_BLOCK, manager.activeHeader().metaBlock());
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            assertEquals(0, manager.activeHeader().iteration());
            assertEquals(StorageFormat.INVALID_BLOCK, manager.activeHeader().metaBlock());
        }
    }
}
