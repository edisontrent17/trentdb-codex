package dev.trentdb.execution.ddl;

import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.QueryExecutor;
import dev.trentdb.execution.QueryResult;
import dev.trentdb.parser.ParsingException;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.BinderException;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransactionalMultiRowInsertTest {
    @TempDir Path dir;

    @Test void multiRowInsertIsPrivateThenCommitsInStatementAndColumnOrder() {
        try (var wal = WriteAheadLog.open(dir.resolve("multi.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            commit(catalog, storage, manager, "CREATE TABLE people (id BIGINT, active BOOLEAN, name TEXT)");
            var writer = manager.startTransaction(); var earlier = manager.startTransaction();
            exec(catalog, storage, manager, writer, "INSERT INTO people (name, id, active) VALUES ('Ada', 1, TRUE), ('Bea', 2, NULL), ('Cid', 3, FALSE)");
            assertEquals(List.of(List.of(1L, true, "Ada"), Arrays.asList(2L, null, "Bea"), List.of(3L, false, "Cid")), rows(catalog, storage, manager, writer));
            assertEquals(List.of(), rows(catalog, storage, manager, earlier)); manager.commit(writer);
            assertEquals(List.of(), rows(catalog, storage, manager, earlier)); assertEquals(List.of(List.of(1L, true, "Ada"), Arrays.asList(2L, null, "Bea"), List.of(3L, false, "Cid")), rows(catalog, storage, manager, manager.startTransaction()));
            var writes = wal.recoverCommittedTransactions().getLast().writes(); assertEquals(3, writes.size()); assertTrue(writes.stream().allMatch(payload -> payload[0] == 3));
        }
    }

    @Test void invalidLaterRowDoesNotDisturbPriorTransactionWorkAndUnsupportedFormsFail() {
        try (var wal = WriteAheadLog.open(dir.resolve("atomic.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager(); commit(catalog, storage, manager, "CREATE TABLE people (id BIGINT)");
            var writer = manager.startTransaction(); exec(catalog, storage, manager, writer, "INSERT INTO people VALUES (1)");
            assertThrows(BinderException.class, () -> exec(catalog, storage, manager, writer, "INSERT INTO people VALUES (2), (wrong)"));
            assertEquals(List.of(List.of(1L)), selectIds(catalog, storage, manager, writer)); manager.commit(writer); assertEquals(List.of(List.of(1L)), selectIds(catalog, storage, manager, manager.startTransaction()));
            assertThrows(ParsingException.class, () -> new SqlParser().parse("INSERT INTO people VALUES ()"));
            assertThrows(ParsingException.class, () -> new SqlParser().parse("INSERT INTO people SELECT id FROM people"));
        }
    }

    @Test void multiRowOp3RecoveryPreservesOrderAndVectorBoundary() {
        try (var wal = WriteAheadLog.open(dir.resolve("replay.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager(); commit(catalog, storage, manager, "CREATE TABLE rows (id BIGINT)");
            var sql = new StringBuilder("INSERT INTO rows VALUES "); for (int value = 1; value <= 2049; value++) { if (value > 1) sql.append(", "); sql.append("(").append(value).append(")"); } commit(catalog, storage, manager, sql.toString());
            var recoveredCatalog = new Catalog(); var recoveredStorage = new StorageManager(); var recovery = new DdlWalRecovery(recoveredCatalog, recoveredStorage); recovery.replay(wal.recoverCommittedTransactions()); var chunks = recoveredStorage.getTable(recoveredCatalog.lookupTable(recovery.startReadTransaction(), new dev.trentdb.ast.QualifiedName(List.of("rows")))).scanChunks();
            assertEquals(2, chunks.size()); assertEquals(2048, chunks.getFirst().cardinality()); assertEquals(1L, chunks.getFirst().column(0).getBigint(0)); assertEquals(2049L, chunks.get(1).column(0).getBigint(0));
        }
    }

    private static void commit(Catalog catalog, StorageManager storage, TransactionManager manager, String sql) { var tx = manager.startTransaction(); exec(catalog, storage, manager, tx, sql); manager.commit(tx); }
    private static QueryResult exec(Catalog c, StorageManager s, TransactionManager m, Transaction tx, String sql) { return new QueryExecutor(s, new TransactionalDdlExecutor(c, s, m, tx)).execute(new LogicalPlanner().plan(new Binder(c).bind(tx, new SqlParser().parse(sql)))); }
    private static List<List<Object>> rows(Catalog c, StorageManager s, TransactionManager m, Transaction tx) { return exec(c, s, m, tx, "SELECT id, active, name FROM people").rows(); }
    private static List<List<Object>> selectIds(Catalog c, StorageManager s, TransactionManager m, Transaction tx) { return exec(c, s, m, tx, "SELECT id FROM people").rows(); }
}
