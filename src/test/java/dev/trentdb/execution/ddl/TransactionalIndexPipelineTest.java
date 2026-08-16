package dev.trentdb.execution.ddl;

import dev.trentdb.TrentDbConnection;
import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.CreateIndexStatement;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.DropIndexStatement;
import dev.trentdb.ast.IndexKey;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.SortDirection;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.parser.ParsingException;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionalIndexPipelineTest {
    @TempDir Path directory;

    @Test
    void parserPreservesAllSelect4T8allDirectionsAndRejectsUnsupportedIndexForms() {
        var statement = assertInstanceOf(CreateIndexStatement.class, new SqlParser().parse(
                "CREATE INDEX t8all ON t8(a ASC, b DESC, c, d DESC)"));
        assertEquals(List.of(
                new IndexKey("a", SortDirection.ASC),
                new IndexKey("b", SortDirection.DESC),
                new IndexKey("c", SortDirection.ASC),
                new IndexKey("d", SortDirection.DESC)), statement.keys());
        assertInstanceOf(DropIndexStatement.class, new SqlParser().parse("DROP INDEX public.t8all"));
        assertThrows(ParsingException.class, () -> new SqlParser().parse("CREATE UNIQUE INDEX x ON t8(a)"));
        assertThrows(ParsingException.class, () -> new SqlParser().parse("CREATE INDEX x ON t8(lower(a))"));
        assertThrows(ParsingException.class, () -> new SqlParser().parse("CREATE INDEX x ON t8(a) WHERE a > 0"));
    }

    @Test
    void catalogIndexCreateDropRollbackSnapshotAndConflictAreVersioned() {
        var catalog = new Catalog();
        var manager = new TransactionManager();
        var createTable = manager.startTransaction();
        catalog.createTable(createTable, table());
        manager.commit(createTable);

        var oldReader = manager.startReadTransaction();
        var writer = manager.startTransaction();
        var index = catalog.createIndex(writer, index());
        assertEquals(List.of(new IndexKey("a", SortDirection.ASC), new IndexKey("b", SortDirection.DESC)), index.keys());
        assertThrows(CatalogException.class, () -> catalog.lookupIndex(oldReader, indexName()));
        manager.commit(writer);
        assertThrows(CatalogException.class, () -> catalog.lookupIndex(oldReader, indexName()));
        var reader = manager.startReadTransaction();
        assertEquals(index.keys(), catalog.lookupIndex(reader, indexName()).keys());

        var rollback = manager.startTransaction();
        catalog.dropIndex(rollback, indexName());
        assertThrows(CatalogException.class, () -> catalog.lookupIndex(rollback, indexName()));
        manager.rollback(rollback);
        assertDoesNotThrow(() -> catalog.lookupIndex(manager.startReadTransaction(), indexName()));

        var first = manager.startTransaction();
        var second = manager.startTransaction();
        catalog.dropIndex(first, indexName());
        manager.commit(first);
        catalog.dropIndex(second, indexName());
        assertThrows(CatalogException.class, () -> manager.commit(second));
    }

    @Test
    void deterministicWalOperationsRecoverThenDropIndex() {
        var create = index();
        var createPayload = DdlWalPayload.createIndex(create);
        var dropPayload = DdlWalPayload.dropIndex(new DropIndexStatement(indexName()));
        assertEquals(6, Byte.toUnsignedInt(createPayload[0]));
        assertEquals(7, Byte.toUnsignedInt(dropPayload[0]));
        assertArrayEquals(createPayload, DdlWalPayload.createIndex(create));

        try (var wal = WriteAheadLog.open(directory.resolve("indexes.wal"))) {
            commit(wal, 1, 1, DdlWalPayload.createTable(table()));
            commit(wal, 2, 2, createPayload);
            var catalog = new Catalog();
            var recovery = new DdlWalRecovery(catalog, new StorageManager());
            recovery.replay(wal.recoverCommittedTransactions());
            assertEquals(create.keys(), catalog.lookupIndex(recovery.startReadTransaction(), indexName()).keys());
            commit(wal, 3, 3, dropPayload);
            recovery.replay(wal.recoverCommittedTransactions());
            assertThrows(CatalogException.class, () -> catalog.lookupIndex(recovery.startReadTransaction(), indexName()));
        }
    }

    @Test
    void publicFacadeReopenRecoversIndexMetadataWithoutChangingSelectSemantics() {
        var walPath = directory.resolve("facade.wal");
        try (var connection = TrentDbConnection.open(walPath)) {
            connection.execute("CREATE TABLE t8 (a BIGINT, b BIGINT)");
            connection.execute("INSERT INTO t8 VALUES (1, 2)");
            connection.execute("CREATE INDEX t8all ON t8(a ASC, b DESC)");
            assertEquals(List.of(List.of(1L, 2L)), connection.execute("SELECT a, b FROM t8").rows());
        }
        try (var reopened = TrentDbConnection.open(walPath)) {
            assertEquals(List.of(List.of(1L, 2L)), reopened.execute("SELECT a, b FROM t8").rows());
            assertDoesNotThrow(() -> reopened.execute("DROP INDEX t8all"));
        }
    }

    private static void commit(WriteAheadLog wal, long transactionId, long version, byte[] payload) {
        wal.appendBegin(transactionId);
        wal.appendWrite(transactionId, payload);
        wal.appendCommit(transactionId, version);
        wal.force();
    }

    private static CreateTableStatement table() {
        return new CreateTableStatement(tableName(), List.of(
                new ColumnDefinition("a", TypeName.INT), new ColumnDefinition("b", TypeName.INT)));
    }

    private static CreateIndexStatement index() {
        return new CreateIndexStatement(indexName(), tableName(), List.of(
                new IndexKey("a", SortDirection.ASC), new IndexKey("b", SortDirection.DESC)));
    }

    private static QualifiedName tableName() { return new QualifiedName(List.of("t8")); }
    private static QualifiedName indexName() { return new QualifiedName(List.of("t8all")); }
}
