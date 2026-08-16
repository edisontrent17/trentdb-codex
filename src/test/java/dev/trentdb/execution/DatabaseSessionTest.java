package dev.trentdb.execution;

import dev.trentdb.planner.BinderException;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.transaction.TransactionManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseSessionTest {
    @TempDir Path dir;

    @Test void autocommitWritesAndPureReadsHaveExpectedWalBoundaries() {
        try (var wal = WriteAheadLog.open(dir.resolve("auto.wal"))) {
            var session = session(wal); session.execute("CREATE TABLE people (id BIGINT)"); session.execute("INSERT INTO people VALUES (1)");
            int framesBeforeRead = wal.readAllRecords().size(); assertEquals(List.of(List.of(1L)), session.execute("SELECT id FROM people").rows()); assertEquals(framesBeforeRead, wal.readAllRecords().size());
            assertEquals(2, wal.recoverCommittedTransactions().size()); assertEquals(1, wal.recoverCommittedTransactions().get(1).writes().size()); assertEquals(3, wal.recoverCommittedTransactions().get(1).writes().getFirst()[0]);
        }
    }

    @Test void explicitTransactionSharesPrivateWritesAndRollbackOrCommitControlsVisibility() {
        try (var wal = WriteAheadLog.open(dir.resolve("explicit.wal"))) {
            var catalog = new Catalog(); var storage = new StorageManager(); var manager = new TransactionManager(wal); var writer = session(catalog, storage, manager); var reader = session(catalog, storage, manager); writer.execute("CREATE TABLE people (id BIGINT)"); writer.execute("BEGIN TRANSACTION"); writer.execute("INSERT INTO people VALUES (1)"); assertEquals(List.of(List.of(1L)), writer.execute("SELECT id FROM people").rows()); assertEquals(List.of(), reader.execute("SELECT id FROM people").rows()); writer.execute("ROLLBACK"); assertFalse(writer.inTransaction()); assertEquals(List.of(), reader.execute("SELECT id FROM people").rows());
            writer.execute("BEGIN"); writer.execute("INSERT INTO people VALUES (2)"); writer.execute("COMMIT"); assertEquals(List.of(List.of(2L)), reader.execute("SELECT id FROM people").rows());
        }
    }

    @Test void failuresAndSnapshotControlStateAreDeterministic() {
        try (var wal = WriteAheadLog.open(dir.resolve("state.wal"))) {
            var catalog = new Catalog(); var storage = new StorageManager(); var manager = new TransactionManager(wal); var first = session(catalog, storage, manager); var second = session(catalog, storage, manager); first.execute("CREATE TABLE people (id BIGINT)"); first.execute("INSERT INTO people VALUES (1)"); first.execute("BEGIN"); assertThrows(SessionException.class, () -> first.execute("BEGIN")); assertThrows(BinderException.class, () -> first.execute("INSERT INTO people VALUES (wrong)")); assertTrue(first.inTransaction()); second.execute("INSERT INTO people VALUES (2)"); assertEquals(List.of(List.of(1L)), first.execute("SELECT id FROM people").rows()); first.execute("INSERT INTO people VALUES (3)"); first.execute("COMMIT"); assertEquals(List.of(List.of(1L), List.of(2L), List.of(3L)), second.execute("SELECT id FROM people").rows());
            assertThrows(SessionException.class, () -> first.execute("COMMIT")); assertThrows(SessionException.class, () -> first.execute("ROLLBACK"));
        }
    }

    private static DatabaseSession session(Catalog catalog, StorageManager storage, TransactionManager manager) { return new DatabaseSession(catalog, storage, manager); }
    private static DatabaseSession session(WriteAheadLog wal) { return new DatabaseSession(new Catalog(), new StorageManager(), new TransactionManager(wal)); }
}
