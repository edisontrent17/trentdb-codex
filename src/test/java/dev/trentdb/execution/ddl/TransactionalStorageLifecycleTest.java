package dev.trentdb.execution.ddl;

import dev.trentdb.ast.QualifiedName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.execution.QueryExecutor;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageException;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.transaction.TransactionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalStorageLifecycleTest {
    @TempDir Path temporaryDirectory;

    @Test
    void rollbackRemovesPrivateCreatedStorage() {
        try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("rollback.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            var transaction = manager.startTransaction();
            execute(catalog, storage, manager, transaction, "CREATE TABLE people (id BIGINT)");
            var table = catalog.lookupTable(transaction, name());
            assertEquals(0, storage.getTable(table).scanChunks().size());
            manager.rollback(transaction);
            assertEquals(TransactionState.ROLLED_BACK, transaction.state());
            assertThrows(CatalogException.class, () -> catalog.lookupTable(manager.startTransaction(), name()));
            assertThrows(StorageException.class, () -> storage.getTable(table));
        }
    }

    @Test
    void createConflictRollsBackLosingStorageAlongsideCatalog() {
        try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("conflict.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            var first = manager.startTransaction(); var second = manager.startTransaction();
            execute(catalog, storage, manager, first, "CREATE TABLE people (id BIGINT)");
            execute(catalog, storage, manager, second, "CREATE TABLE people (id BIGINT)");
            var firstTable = catalog.lookupTable(first, name()); var secondTable = catalog.lookupTable(second, name());
            manager.commit(first);
            assertThrows(CatalogException.class, () -> manager.commit(second));
            assertEquals(TransactionState.ROLLED_BACK, second.state());
            assertEquals(0, storage.getTable(firstTable).scanChunks().size());
            assertThrows(StorageException.class, () -> storage.getTable(secondTable));
        }
    }

    @Test
    void committedDropRetiresButRetainsStorageForEarlierCatalogSnapshot() {
        try (var wal = WriteAheadLog.open(temporaryDirectory.resolve("drop.wal"))) {
            var manager = new TransactionManager(wal); var catalog = new Catalog(); var storage = new StorageManager();
            var creator = manager.startTransaction();
            execute(catalog, storage, manager, creator, "CREATE TABLE people (id BIGINT)"); manager.commit(creator);
            var reader = manager.startTransaction(); var original = catalog.lookupTable(reader, name());
            var dropper = manager.startTransaction();
            execute(catalog, storage, manager, dropper, "DROP TABLE people"); manager.commit(dropper);
            assertEquals(0, storage.getTable(original).scanChunks().size());
            assertEquals(true, storage.isRetired(original));
            assertThrows(CatalogException.class, () -> catalog.lookupTable(manager.startTransaction(), name()));
        }
    }

    private static QualifiedName name() { return new QualifiedName(List.of("people")); }
    private static void execute(Catalog catalog, StorageManager storage, TransactionManager manager, Transaction transaction, String sql) {
        var logical = new LogicalPlanner().plan(new Binder(catalog).bind(transaction, new SqlParser().parse(sql)));
        new QueryExecutor(storage, new TransactionalDdlExecutor(catalog, storage, manager, transaction)).execute(logical);
    }
}
