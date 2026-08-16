package dev.trentdb.execution.ddl;

import dev.trentdb.ast.QualifiedName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.execution.QueryExecutor;
import dev.trentdb.parser.ParsingException;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.BoundCreateTableStatement;
import dev.trentdb.planner.BoundDropTableStatement;
import dev.trentdb.planner.logical.LogicalCreateTable;
import dev.trentdb.planner.logical.LogicalDropTable;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.StorageException;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.transaction.TransactionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalDdlPipelineTest {
    @TempDir Path temporaryDirectory;
    private final SqlParser parser = new SqlParser();
    private final LogicalPlanner logicalPlanner = new LogicalPlanner();

    @Test
    void createFlowsThroughPipelineStaysPrivateThenCommitsWithWalIntent() {
        var path = temporaryDirectory.resolve("ddl.wal");
        try (var wal = WriteAheadLog.open(path)) {
            var manager = new TransactionManager(wal);
            var catalog = new Catalog();
            var storage = new StorageManager();
            var writer = manager.startTransaction();
            var earlierReader = manager.startTransaction();

            var logical = logical(catalog, writer, "CREATE TABLE people (id BIGINT, name TEXT)");
            assertInstanceOf(LogicalCreateTable.class, logical);
            var result = new QueryExecutor(storage, new TransactionalDdlExecutor(catalog, storage, manager, writer)).execute(logical);

            assertEquals(List.of(), result.columns());
            assertEquals(List.of(), result.rows());
            assertEquals(2, catalog.lookupTable(writer, new QualifiedName(List.of("people"))).columns().size());
            assertThrows(CatalogException.class, () -> catalog.lookupTable(earlierReader, new QualifiedName(List.of("people"))));

            manager.commit(writer);

            assertThrows(CatalogException.class, () -> catalog.lookupTable(earlierReader, new QualifiedName(List.of("people"))));
            assertEquals(1, wal.recoverCommittedTransactions().size());
            assertEquals(1, wal.recoverCommittedTransactions().getFirst().writes().getFirst()[0]);
            assertEquals(2, catalog.lookupTable(manager.startTransaction(), new QualifiedName(List.of("people"))).columns().size());
            assertEquals(0, storage.getTable(catalog.lookupTable(manager.startTransaction(), new QualifiedName(List.of("people")))).scanChunks().size());
        }
    }

    @Test
    void dropFlowsThroughPipelineAndPreservesEarlierSnapshot() {
        var path = temporaryDirectory.resolve("drop.wal");
        try (var wal = WriteAheadLog.open(path)) {
            var manager = new TransactionManager(wal);
            var catalog = new Catalog();
            var storage = new StorageManager();
            var creator = manager.startTransaction();
            execute(catalog, storage, manager, creator, "CREATE TABLE people (id BIGINT)");
            manager.commit(creator);

            var earlierReader = manager.startTransaction();
            var dropper = manager.startTransaction();
            var logical = logical(catalog, dropper, "DROP TABLE people");
            assertInstanceOf(LogicalDropTable.class, logical);
            new QueryExecutor(storage, new TransactionalDdlExecutor(catalog, storage, manager, dropper)).execute(logical);
            manager.commit(dropper);

            var original = catalog.lookupTable(earlierReader, new QualifiedName(List.of("people")));
            assertEquals(true, storage.isRetired(original));
            assertEquals(0, storage.getTable(original).scanChunks().size());
            assertEquals(1, catalog.lookupTable(earlierReader, new QualifiedName(List.of("people"))).columns().size());
            assertThrows(CatalogException.class, () -> catalog.lookupTable(manager.startTransaction(), new QualifiedName(List.of("people"))));
            assertEquals(2, wal.recoverCommittedTransactions().size());
            assertEquals(2, wal.recoverCommittedTransactions().getLast().writes().getFirst()[0]);
        }
    }

    @Test
    void unsupportedDdlModifiersFailDuringParsing() {
        assertThrows(ParsingException.class, () -> parser.parse("CREATE TEMP TABLE people (id BIGINT)"));
        assertThrows(ParsingException.class, () -> parser.parse("DROP TABLE IF EXISTS people"));
    }

    private dev.trentdb.planner.logical.LogicalOperator logical(Catalog catalog, Transaction transaction, String sql) {
        var bound = new Binder(catalog).bind(transaction, parser.parse(sql));
        if (sql.startsWith("CREATE")) assertInstanceOf(BoundCreateTableStatement.class, bound);
        else assertInstanceOf(BoundDropTableStatement.class, bound);
        return logicalPlanner.plan(bound);
    }

    private void execute(Catalog catalog, StorageManager storage, TransactionManager manager, Transaction transaction, String sql) {
        new QueryExecutor(storage, new TransactionalDdlExecutor(catalog, storage, manager, transaction))
                .execute(logical(catalog, transaction, sql));
    }
}
