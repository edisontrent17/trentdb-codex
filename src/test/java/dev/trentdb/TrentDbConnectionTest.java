package dev.trentdb;

import dev.trentdb.execution.SessionException;
import dev.trentdb.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrentDbConnectionTest {
    @TempDir Path directory;

    @Test
    void publicConnectionRunsTransactionalDmlThroughTheSessionPipeline() {
        Path walPath = directory.resolve("connection.wal");
        try (var connection = TrentDbConnection.open(walPath)) {
            connection.execute("CREATE TABLE people (id BIGINT, note TEXT)");
            connection.execute("INSERT INTO people VALUES (1, 'one'), (2, 'two')");

            connection.execute("BEGIN TRANSACTION");
            connection.execute("UPDATE people SET id = id + 9 WHERE id = 1");
            connection.execute("DELETE FROM people WHERE id = 2");
            assertEquals(List.of(List.of(10L, "one")), connection.execute("SELECT id, note FROM people").rows());
            connection.execute("COMMIT");
            assertFalse(connection.inTransaction());
            assertEquals(List.of(List.of(10L, "one")), connection.execute("SELECT id, note FROM people").rows());

            connection.execute("BEGIN");
            connection.execute("INSERT INTO people VALUES (3, 'private')");
            connection.execute("DELETE FROM people WHERE id = 10");
            assertEquals(List.of(List.of(3L, "private")), connection.execute("SELECT id, note FROM people").rows());
            connection.execute("ROLLBACK");
            assertEquals(List.of(List.of(10L, "one")), connection.execute("SELECT id, note FROM people").rows());

            assertThrows(SessionException.class, () -> connection.execute("COMMIT"));
            connection.execute("BEGIN");
            assertTrue(connection.inTransaction());
            assertThrows(SessionException.class, () -> connection.execute("BEGIN"));
            connection.execute("ROLLBACK");
        }

        try (var reopened = TrentDbConnection.open(walPath)) {
            assertEquals(List.of(List.of(10L, "one")), reopened.execute("SELECT id, note FROM people").rows());
            reopened.execute("BEGIN");
            reopened.execute("UPDATE people SET id = id + 10 WHERE id = 10");
            reopened.execute("COMMIT");
            assertEquals(List.of(List.of(20L, "one")), reopened.execute("SELECT id, note FROM people").rows());
        }

        try (var reopenedAgain = TrentDbConnection.open(walPath)) {
            assertEquals(List.of(List.of(20L, "one")), reopenedAgain.execute("SELECT id, note FROM people").rows());
        }

        try (var wal = WriteAheadLog.open(walPath)) {
            // CREATE, two-row INSERT, committed UPDATE+DELETE, rolled-back INSERT+DELETE, then recovered UPDATE.
            // Pure SELECT and read-only BEGIN/ROLLBACK add no frames.
            assertEquals(18, wal.readAllRecords().size());
            assertEquals(List.of(1L, 2L, 3L, 7L), wal.recoverCommittedTransactions().stream().map(transaction -> transaction.transactionId()).toList());
        }
    }
    @Test
    void incompleteTransactionsAreIgnoredAndReserveTheirTransactionIds() {
        Path walPath = directory.resolve("incomplete.wal");
        try (var wal = WriteAheadLog.open(walPath)) {
            wal.appendBegin(41);
            wal.appendWrite(41, new byte[]{99});
            wal.force();
        }

        try (var connection = TrentDbConnection.open(walPath)) {
            connection.execute("CREATE TABLE recovered (id BIGINT)");
        }
        try (var reopened = TrentDbConnection.open(walPath)) {
            assertEquals(List.of(), reopened.execute("SELECT id FROM recovered").rows());
        }
        try (var wal = WriteAheadLog.open(walPath)) {
            assertEquals(5, wal.readAllRecords().size());
            assertEquals(List.of(42L), wal.recoverCommittedTransactions().stream().map(transaction -> transaction.transactionId()).toList());
        }
    }

    @Test
    void legacyOrCorruptWalIsRejectedWithoutChangingItsBytes() throws Exception {
        Path walPath = directory.resolve("legacy.wal");
        byte[] legacy = new byte[]{0x54, 0x57, 0x41, 0x4c};
        Files.write(walPath, legacy);

        assertThrows(RuntimeException.class, () -> TrentDbConnection.open(walPath));
        assertArrayEquals(legacy, Files.readAllBytes(walPath));
    }
}
