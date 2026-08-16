package dev.trentdb;

import dev.trentdb.storage.DuckDbSchemaCreateInfo;
import dev.trentdb.storage.DuckDbSequenceCreateInfo;
import dev.trentdb.storage.DuckDbV2CheckpointPublisher;
import dev.trentdb.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrentDbConnectionDuckDbV2ImportTest {
    @TempDir
    Path directory;

    @Test
    void exportImportSelectUpdateCommitAndWalReopenRetainsPrimitiveRows() throws Exception {
        Path sourceWal = directory.resolve("source.wal");
        Path source = directory.resolve("snapshot.duckdb");
        try (TrentDbConnection connection = TrentDbConnection.open(sourceWal)) {
            connection.execute("CREATE TABLE metrics (flag BOOLEAN, i INTEGER, b BIGINT)");
            connection.execute("INSERT INTO metrics VALUES (TRUE, 7, 9), (FALSE, NULL, -3)");
            connection.exportDuckDbV2(source);
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        Path importedWal = directory.resolve("imported.wal");
        try (TrentDbConnection imported = TrentDbConnection.openDuckDbV2(source, importedWal)) {
            assertEquals(List.of(List.of(true, 7, 9L), Arrays.asList(false, null, -3L)),
                    imported.execute("SELECT flag, i, b FROM metrics").rows());
            imported.execute("BEGIN");
            imported.execute("UPDATE metrics SET b = b + 1 WHERE flag = TRUE");
            imported.execute("COMMIT");
            assertEquals(List.of(List.of(10L), List.of(-3L)), imported.execute("SELECT b FROM metrics").rows());
        }
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        try (TrentDbConnection reopened = TrentDbConnection.open(importedWal)) {
            assertEquals(List.of(List.of(true, 7, 10L), Arrays.asList(false, null, -3L)),
                    reopened.execute("SELECT flag, i, b FROM metrics").rows());
        }
    }

    @Test
    void exportsAndImportsAnEmptyPrimitiveTable() throws Exception {
        Path source = directory.resolve("empty.duckdb");
        try (TrentDbConnection connection = TrentDbConnection.open(directory.resolve("empty-source.wal"))) {
            connection.execute("CREATE TABLE empty_values (n INTEGER)");
            connection.exportDuckDbV2(source);
        }
        try (TrentDbConnection imported = TrentDbConnection.openDuckDbV2(source, directory.resolve("empty-import.wal"))) {
            assertEquals(List.of(), imported.execute("SELECT n FROM empty_values").rows());
            imported.execute("INSERT INTO empty_values VALUES (42)");
            assertEquals(List.of(List.of(42)), imported.execute("SELECT n FROM empty_values").rows());
        }
    }

    @Test
    void exportsAndImports2049RowsAcrossAlignedDataAndValiditySegments() throws Exception {
        Path source = directory.resolve("multi-vector.duckdb");
        try (TrentDbConnection connection = TrentDbConnection.open(directory.resolve("multi-source.wal"))) {
            connection.execute("CREATE TABLE numbers (n INTEGER)");
            StringBuilder sql = new StringBuilder("INSERT INTO numbers VALUES ");
            for (int value = 0; value <= 2048; value++) {
                if (value > 0) sql.append(',');
                if (value == 1024) {
                    sql.append("(NULL)");
                } else {
                    sql.append('(').append(value).append(')');
                }
            }
            connection.execute(sql.toString());
            connection.exportDuckDbV2(source);
        }
        try (TrentDbConnection imported = TrentDbConnection.openDuckDbV2(source, directory.resolve("multi-import.wal"))) {
            List<List<Object>> rows = imported.execute("SELECT n FROM numbers").rows();
            assertEquals(2049, rows.size());
            assertEquals(0, rows.getFirst().getFirst());
            assertEquals(null, rows.get(1024).getFirst());
            assertEquals(2048, rows.getLast().getFirst());
        }
    }

    @Test
    void unsupportedSourceDoesNotChangeSourceOrExistingWalTarget() throws Exception {
        Path source = directory.resolve("sequence.duckdb");
        DuckDbSchemaCreateInfo schema = new DuckDbSchemaCreateInfo(List.of("memory", "public"), false, false,
                DuckDbSchemaCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "");
        DuckDbSequenceCreateInfo sequence = new DuckDbSequenceCreateInfo(List.of("memory", "public", "s"), false,
                false, DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "s", 0, 1, 1,
                Long.MAX_VALUE, 1, false, OptionalLong.empty());
        DuckDbV2CheckpointPublisher.create(source, new byte[16],
                new DuckDbV2CheckpointPublisher.Checkpoint(List.of(schema), List.of(sequence), List.of()));
        byte[] sourceBytes = Files.readAllBytes(source);
        Path wal = directory.resolve("retain.wal");
        byte[] priorWal = new byte[]{7, 8, 9};
        Files.write(wal, priorWal);

        assertThrows(StorageException.class, () -> TrentDbConnection.openDuckDbV2(source, wal));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertArrayEquals(priorWal, Files.readAllBytes(wal));
    }

    @Test
    void corruptPayloadSourceDoesNotChangeExistingWalTarget() throws Exception {
        Path source = directory.resolve("corrupt.duckdb");
        try (TrentDbConnection connection = TrentDbConnection.open(directory.resolve("corrupt-source.wal"))) {
            connection.execute("CREATE TABLE values_t (n INTEGER)");
            connection.execute("INSERT INTO values_t VALUES (1)");
            connection.exportDuckDbV2(source);
        }
        byte[] bytes = Files.readAllBytes(source);
        bytes[(int) dev.trentdb.storage.format.StorageFormat.BLOCK_START] ^= 1;
        Files.write(source, bytes);
        Path wal = directory.resolve("corrupt-retain.wal");
        byte[] priorWal = new byte[]{1, 2, 3};
        Files.write(wal, priorWal);

        assertThrows(StorageException.class, () -> TrentDbConnection.openDuckDbV2(source, wal));
        assertArrayEquals(priorWal, Files.readAllBytes(wal));
    }
}
