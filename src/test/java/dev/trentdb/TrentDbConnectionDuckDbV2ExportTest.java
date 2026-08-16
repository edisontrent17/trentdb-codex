package dev.trentdb;

import dev.trentdb.storage.DuckDbCheckpointEnvelopeReader;
import dev.trentdb.storage.DuckDbPrimitiveColumnMetadata;
import dev.trentdb.storage.DuckDbPrimitiveColumnMetadataReader;
import dev.trentdb.storage.DuckDbPrimitivePayloadReader;
import dev.trentdb.storage.DuckDbRowGroupHeaderReader;
import dev.trentdb.storage.DuckDbRowGroupHeaders;
import dev.trentdb.storage.DuckDbTableCreateInfo;
import dev.trentdb.storage.DuckDbTableEntryEnvelope;
import dev.trentdb.storage.SingleFileBlockManager;
import dev.trentdb.storage.StorageException;
import dev.trentdb.storage.format.MetaBlockPointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrentDbConnectionDuckDbV2ExportTest {
    @TempDir
    Path directory;

    @Test
    void exportsCommittedPrimitiveFacadeRowsAndReopensThroughV2Readers() {
        Path wal = directory.resolve("source.wal");
        Path target = directory.resolve("snapshot.duckdb");
        try (TrentDbConnection connection = TrentDbConnection.open(wal)) {
            connection.execute("CREATE TABLE metrics (flag BOOLEAN, i INTEGER, b BIGINT)");
            connection.execute("INSERT INTO metrics VALUES (TRUE, 7, 9), (FALSE, NULL, -3)");
            connection.exportDuckDbV2(target);
        }

        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(target)) {
            DuckDbCheckpointEnvelopeReader catalog = DuckDbCheckpointEnvelopeReader.openActiveCheckpoint(manager);
            assertEquals(2, catalog.beginCheckpoint());
            catalog.readNextEntryEnvelope();
            assertEquals(List.of("memory", "public"), catalog.readSchemaCreateInfo().qualifiedNamePath());
            catalog.readNextEntryEnvelope();
            DuckDbTableEntryEnvelope table = catalog.readTableEntryEnvelope();
            assertEquals("metrics", table.createInfo().tableName());
            assertEquals(2, table.totalRows());
            assertEquals(List.of(DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN,
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER,
                    DuckDbTableCreateInfo.ScalarLogicalType.BIGINT),
                    table.createInfo().columns().stream().map(DuckDbTableCreateInfo.Column::type).toList());

            List<DuckDbTableCreateInfo.ScalarLogicalType> types = table.createInfo().columns().stream()
                    .map(DuckDbTableCreateInfo.Column::type).toList();
            DuckDbRowGroupHeaders groups = new DuckDbRowGroupHeaderReader(manager,
                    new MetaBlockPointer(table.tablePointer().packedBlockPointer(), (int) table.tablePointer().offset()),
                    types).read();
            assertEquals(2, groups.groups().getFirst().tupleCount());
            assertEquals(Arrays.asList(1L, 0L), values(manager, groups, types.get(0), 0));
            assertEquals(Arrays.asList(7L, null), values(manager, groups, types.get(1), 1));
            assertEquals(Arrays.asList(9L, -3L), values(manager, groups, types.get(2), 2));
        }
    }

    @Test
    void rejectedExportsDoNotChangeExistingTargetBytes() throws Exception {
        Path target = directory.resolve("retain.duckdb");
        byte[] original = new byte[]{4, 5, 6};
        Files.write(target, original);
        try (TrentDbConnection connection = TrentDbConnection.open(directory.resolve("unsupported.wal"))) {
            connection.execute("CREATE TABLE notes (note TEXT)");
            connection.execute("INSERT INTO notes VALUES ('not exportable')");
            assertThrows(StorageException.class, () -> connection.exportDuckDbV2(target));
            assertArrayEquals(original, Files.readAllBytes(target));
        }
    }

    @Test
    void activeTransactionIsRejectedWithoutChangingTarget() throws Exception {
        Path target = directory.resolve("active.duckdb");
        byte[] original = new byte[]{7, 8, 9};
        Files.write(target, original);
        try (TrentDbConnection connection = TrentDbConnection.open(directory.resolve("active.wal"))) {
            connection.execute("CREATE TABLE numbers (n BIGINT)");
            connection.execute("INSERT INTO numbers VALUES (1)");
            connection.execute("BEGIN");
            connection.execute("INSERT INTO numbers VALUES (2)");
            assertThrows(IllegalStateException.class, () -> connection.exportDuckDbV2(target));
            assertArrayEquals(original, Files.readAllBytes(target));
            connection.execute("ROLLBACK");
        }
    }

    private static List<Long> values(SingleFileBlockManager manager, DuckDbRowGroupHeaders groups,
                                     DuckDbTableCreateInfo.ScalarLogicalType type, int columnIndex) {
        DuckDbPrimitiveColumnMetadata metadata = new DuckDbPrimitiveColumnMetadataReader(manager,
                groups.groups().getFirst().dataPointers().get(columnIndex), type).read();
        return new DuckDbPrimitivePayloadReader(manager).read(type, metadata.dataSegments().getFirst(),
                metadata.validitySegments().isEmpty() ? null : metadata.validitySegments().getFirst());
    }
}
