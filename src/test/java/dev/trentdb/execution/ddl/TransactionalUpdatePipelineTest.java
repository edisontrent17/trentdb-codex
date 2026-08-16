package dev.trentdb.execution.ddl;

import dev.trentdb.ast.*;
import dev.trentdb.catalog.*;
import dev.trentdb.execution.*;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.*;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.*;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.*;
import dev.trentdb.types.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransactionalUpdatePipelineTest {
    @TempDir Path dir;

    @Test void updatePreservesRowIdsAndSnapshotValuesAcrossNullOwnInsertRollbackAndFailure() {
        try (var wal = WriteAheadLog.open(dir.resolve("update.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            commit(manager, catalog, storage, "CREATE TABLE people (id BIGINT, note TEXT)");
            commit(manager, catalog, storage, "INSERT INTO people VALUES (1, 'keep')");
            commit(manager, catalog, storage, "INSERT INTO people VALUES (2, NULL)");
            var old = manager.startTransaction(); var writer = manager.startTransaction();
            exec(catalog, storage, manager, writer, "UPDATE people SET id = id + 10 WHERE note IS NULL OR id = 1");
            exec(catalog, storage, manager, writer, "UPDATE people SET note = NULL WHERE id = 11");
            assertEquals(List.of(Arrays.asList(11L, null), Arrays.asList(12L, null)), rows(catalog, storage, manager, writer));
            assertEquals(List.of(List.of(1L, "keep"), Arrays.asList(2L, null)), rows(catalog, storage, manager, old));
            manager.commit(writer);
            assertEquals(List.of(List.of(1L, "keep"), Arrays.asList(2L, null)), rows(catalog, storage, manager, old));
            assertEquals(List.of(Arrays.asList(11L, null), Arrays.asList(12L, null)), rows(catalog, storage, manager, manager.startTransaction()));
            var own = manager.startTransaction(); exec(catalog, storage, manager, own, "INSERT INTO people VALUES (3, 'private')"); exec(catalog, storage, manager, own, "UPDATE people SET id = id + 10 WHERE id = 3"); exec(catalog, storage, manager, own, "UPDATE people SET id = id + 1 WHERE id = 13");
            assertEquals(List.of(Arrays.asList(11L, null), Arrays.asList(12L, null), List.of(14L, "private")), rows(catalog, storage, manager, own)); manager.commit(own);
            var rollback = manager.startTransaction(); exec(catalog, storage, manager, rollback, "UPDATE people SET id = id + 1 WHERE id = 12"); manager.rollback(rollback);
            assertEquals(List.of(Arrays.asList(11L, null), Arrays.asList(12L, null), List.of(14L, "private")), rows(catalog, storage, manager, manager.startTransaction()));
            var failing = manager.startTransaction(); exec(catalog, storage, manager, failing, "UPDATE people SET id = id + 1 WHERE id = 14"); failing.enlist(new TransactionParticipant(){ public void prepareCommit(Transaction tx,long version){} public void commit(Transaction tx,long version){ throw new IllegalStateException("fail"); } public void rollback(Transaction tx){} }); assertThrows(IllegalStateException.class, () -> manager.commit(failing));
            assertEquals(List.of(Arrays.asList(11L, null), Arrays.asList(12L, null), List.of(14L, "private")), rows(catalog, storage, manager, manager.startTransaction()));
            assertTrue(wal.recoverCommittedTransactions().stream().flatMap(tx -> tx.writes().stream()).anyMatch(payload -> payload[0] == 5));
        }
    }

    @Test void updateWalReplayAppliesRecordedFullRowsWithoutExpressionEvaluation() {
        try (var wal = WriteAheadLog.open(dir.resolve("replay.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            commit(manager, catalog, storage, "CREATE TABLE people (id BIGINT, note TEXT)"); commit(manager, catalog, storage, "INSERT INTO people VALUES (1, 'old')"); commit(manager, catalog, storage, "INSERT INTO people VALUES (2, 'keep')"); commit(manager, catalog, storage, "UPDATE people SET note = 'new' WHERE id = 1");
            var recoveredCatalog = new Catalog(); var recoveredStorage = new StorageManager(); var recovery = new DdlWalRecovery(recoveredCatalog, recoveredStorage); recovery.replay(wal.recoverCommittedTransactions());
            var chunk = recoveredStorage.getTable(recoveredCatalog.lookupTable(recovery.startReadTransaction(), new QualifiedName(List.of("people")))).scanChunks().getFirst();
            assertEquals(2, chunk.cardinality()); assertEquals("new", chunk.column(1).getText(0)); assertEquals("keep", chunk.column(1).getText(1));
        }
    }

    @Test void updateRejectsUnsupportedSetExpressionsAndPredicates() {
        var catalog = new Catalog(); var manager = new TransactionManager(); var create = manager.startTransaction(); catalog.createTable(create, new CreateTableStatement(new QualifiedName(List.of("people")), List.of(new ColumnDefinition("id", TypeName.BIGINT), new ColumnDefinition("note", TypeName.TEXT)))); manager.commit(create); var read = manager.startTransaction(); var binder = new Binder(catalog);
        assertThrows(BinderException.class, () -> binder.bind(read, new SqlParser().parse("UPDATE people SET note = lower(note)")));
        assertThrows(BinderException.class, () -> binder.bind(read, new SqlParser().parse("UPDATE people SET id = 1 WHERE EXISTS (SELECT id FROM people)")));
        assertThrows(BinderException.class, () -> binder.bind(read, new SqlParser().parse("UPDATE people SET id = 1 WHERE 1")));
        assertThrows(BinderException.class, () -> binder.bind(read, new SqlParser().parse("UPDATE people SET note = 1")));
    }

    private static void commit(TransactionManager manager, Catalog catalog, StorageManager storage, String sql) { var tx = manager.startTransaction(); exec(catalog, storage, manager, tx, sql); manager.commit(tx); }
    @Test void integerUpdateOverflowDoesNotMutateRowsOrAppendWalIntent() { try (var wal = WriteAheadLog.open(dir.resolve("integer-update-overflow.wal"))) { var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager(); commit(manager, catalog, storage, "CREATE TABLE people (id INTEGER, note TEXT)"); commit(manager, catalog, storage, "INSERT INTO people VALUES (7, 'old')"); var writer = manager.startTransaction(); int recordsBefore = wal.readAllRecords().size(); var failure = assertThrows(BinderException.class, () -> exec(catalog, storage, manager, writer, "UPDATE people SET id = 2147483648")); assertEquals("BIGINT literal is out of range for INTEGER: 2147483648", failure.getMessage()); assertEquals(List.of(List.of(7, "old")), rows(catalog, storage, manager, writer)); assertEquals(recordsBefore, wal.readAllRecords().size()); assertEquals(0L, wal.readAllRecords().stream().filter(record -> record.transactionId() == writer.id() && record.type() == dev.trentdb.storage.wal.WalRecordType.WRITE).count()); } }
    @Test void integerUpdateRejectsNonLiteralBigintColumnAndExpressionNarrowing() { var catalog = new Catalog(); var manager = new TransactionManager(); var create = manager.startTransaction(); catalog.createTable(create, new CreateTableStatement(new QualifiedName(List.of("people")), List.of(new ColumnDefinition("id", TypeName.INT), new ColumnDefinition("source", TypeName.BIGINT)))); manager.commit(create); var read = manager.startTransaction(); var binder = new Binder(catalog); var column = assertThrows(BinderException.class, () -> binder.bind(read, new SqlParser().parse("UPDATE people SET id = source"))); assertEquals("UPDATE value type BIGINT cannot be assigned to INTEGER", column.getMessage()); var expression = assertThrows(BinderException.class, () -> binder.bind(read, new SqlParser().parse("UPDATE people SET id = source + 0"))); assertEquals("UPDATE value type BIGINT cannot be assigned to INTEGER", expression.getMessage()); }
    private static QueryResult exec(Catalog catalog, StorageManager storage, TransactionManager manager, Transaction tx, String sql) { return new QueryExecutor(storage, new TransactionalDdlExecutor(catalog, storage, manager, tx)).execute(new LogicalPlanner().plan(new Binder(catalog).bind(tx, new SqlParser().parse(sql)))); }
    private static List<List<Object>> rows(Catalog catalog, StorageManager storage, TransactionManager manager, Transaction tx) { return exec(catalog, storage, manager, tx, "SELECT id, note FROM people").rows(); }
}
