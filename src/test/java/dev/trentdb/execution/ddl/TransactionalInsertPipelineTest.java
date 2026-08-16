package dev.trentdb.execution.ddl;

import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.QueryResult;
import dev.trentdb.execution.QueryExecutor;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.BinderException;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalInsertPipelineTest {
    @TempDir Path temporaryDirectory;

    @Test
    void insertIsPrivateToWriterThenVisibleOnlyToFutureSnapshots() {
        try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("insert.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            var creator = manager.startTransaction(); execute(catalog, storage, manager, creator, "CREATE TABLE people (id BIGINT, name TEXT)"); manager.commit(creator);
            var writer = manager.startTransaction(); var earlierReader = manager.startTransaction();
            execute(catalog, storage, manager, writer, "INSERT INTO people (name, id) VALUES ('Alice', 1)");
            assertEquals(List.of(List.of(1L, "Alice")), select(catalog, storage, manager, writer));
            assertEquals(List.of(), select(catalog, storage, manager, earlierReader));
            manager.commit(writer);
            assertEquals(List.of(), select(catalog, storage, manager, earlierReader));
            assertEquals(List.of(List.of(1L, "Alice")), select(catalog, storage, manager, manager.startTransaction()));
            assertEquals(2, wal.recoverCommittedTransactions().size());
            assertEquals(3, wal.recoverCommittedTransactions().getLast().writes().getFirst()[0]);
        }
    }

    @Test
    void rollbackDiscardsPrivateRowsAndNullIsTyped() {
        try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("rollback-insert.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            var creator = manager.startTransaction(); execute(catalog, storage, manager, creator, "CREATE TABLE people (id BIGINT, active BOOLEAN, name TEXT)"); manager.commit(creator);
            var writer = manager.startTransaction();
            execute(catalog, storage, manager, writer, "INSERT INTO people VALUES (1, NULL, 'Alice')");
            assertEquals(List.of(java.util.Arrays.asList(1L, null, "Alice")), execute(catalog, storage, manager, writer, "SELECT id, active, name FROM people").rows());
            manager.rollback(writer);
            assertEquals(List.of(), select(catalog, storage, manager, manager.startTransaction()));
        }
    }

    @Test
    void rejectsNonLiteralAndIncompatibleValuesBeforeStorageMutation() {
        try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("types.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            var creator = manager.startTransaction(); execute(catalog, storage, manager, creator, "CREATE TABLE people (id BIGINT)"); manager.commit(creator);
            var transaction = manager.startTransaction();
            assertThrows(BinderException.class, () -> execute(catalog, storage, manager, transaction, "INSERT INTO people VALUES (wrong)"));
            assertThrows(BinderException.class, () -> execute(catalog, storage, manager, transaction, "INSERT INTO people VALUES (id)"));
            assertEquals(List.of(), execute(catalog, storage, manager, transaction, "SELECT id FROM people").rows());
        }
    }

    private static List<List<Object>> select(Catalog catalog, StorageManager storage, TransactionManager manager, Transaction transaction) {
        return execute(catalog, storage, manager, transaction, "SELECT id, name FROM people").rows();
    }

    private static QueryResult execute(Catalog catalog, StorageManager storage, TransactionManager manager, Transaction transaction, String sql) {
        var logical = new LogicalPlanner().plan(new Binder(catalog).bind(transaction, new SqlParser().parse(sql)));
        return new QueryExecutor(storage, new TransactionalDdlExecutor(catalog, storage, manager, transaction)).execute(logical);
    }
    @Test void integerBigintLiteralNarrowingAcceptsBothBoundsAndNull() { try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("integer-bounds.wal"))) { var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager(); var creator = manager.startTransaction(); execute(catalog, storage, manager, creator, "CREATE TABLE numbers (value INTEGER)"); manager.commit(creator); var writer = manager.startTransaction(); execute(catalog, storage, manager, writer, "INSERT INTO numbers VALUES (-2147483648), (2147483647), (NULL)"); assertEquals(List.of(List.of(Integer.MIN_VALUE), List.of(Integer.MAX_VALUE), java.util.Arrays.asList((Object) null)), execute(catalog, storage, manager, writer, "SELECT value FROM numbers").rows()); } }
    @Test void integerBigintLiteralNarrowingRejectsOnePastEitherBoundBeforeWriteIntent() { try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("integer-overflow.wal"))) { var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager(); var creator = manager.startTransaction(); execute(catalog, storage, manager, creator, "CREATE TABLE numbers (value INTEGER)"); manager.commit(creator); var writer = manager.startTransaction(); int recordsBefore = wal.readAllRecords().size(); var above = assertThrows(BinderException.class, () -> execute(catalog, storage, manager, writer, "INSERT INTO numbers VALUES (2147483648)")); assertEquals("BIGINT literal is out of range for INTEGER: 2147483648", above.getMessage()); var below = assertThrows(BinderException.class, () -> execute(catalog, storage, manager, writer, "INSERT INTO numbers VALUES (-2147483649)")); assertEquals("BIGINT literal is out of range for INTEGER: -2147483649", below.getMessage()); assertEquals(recordsBefore, wal.readAllRecords().size()); assertEquals(0L, wal.readAllRecords().stream().filter(record -> record.transactionId() == writer.id() && record.type() == dev.trentdb.storage.wal.WalRecordType.WRITE).count()); } }
    @Test void laterIntegerOverflowInMultiRowInsertLeavesStatementAtomicAndUnjournaled() { try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("integer-atomicity.wal"))) { var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager(); var creator = manager.startTransaction(); execute(catalog, storage, manager, creator, "CREATE TABLE numbers (value INTEGER)"); manager.commit(creator); var writer = manager.startTransaction(); int recordsBefore = wal.readAllRecords().size(); assertThrows(BinderException.class, () -> execute(catalog, storage, manager, writer, "INSERT INTO numbers VALUES (7), (2147483648)")); assertEquals(List.of(), execute(catalog, storage, manager, writer, "SELECT value FROM numbers").rows()); assertEquals(recordsBefore, wal.readAllRecords().size()); assertEquals(0L, wal.readAllRecords().stream().filter(record -> record.transactionId() == writer.id() && record.type() == dev.trentdb.storage.wal.WalRecordType.WRITE).count()); } }
}
