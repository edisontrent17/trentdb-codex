package dev.trentdb.transaction;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionManagerWalTest {
    @TempDir Path temporaryDirectory;

    @Test
    void forcesCommitRecordBeforePublishingCatalogVisibility() {
        var path = temporaryDirectory.resolve("catalog.wal");
        try (var wal = WriteAheadLog.open(path)) {
            var manager = new TransactionManager(wal);
            var catalog = new Catalog();
            var transaction = manager.startTransaction();
            catalog.createTable(transaction, new QualifiedName(List.of("people")), List.of(new ColumnDefinition("id", TypeName.BIGINT)));
            manager.recordWrite(transaction, new byte[] {42});
            manager.commit(transaction);

            assertEquals(1, manager.committedCatalogVersion());
            assertEquals(1, wal.recoverCommittedTransactions().size());
            assertEquals(1, catalog.lookupTable(manager.startTransaction(), new QualifiedName(List.of("people"))).columns().size());
        }
    }

    @Test
    void failedPublicationWritesCompensatingRollbackForRecovery() {
        var path = temporaryDirectory.resolve("abort.wal");
        try (var wal = WriteAheadLog.open(path)) {
            var manager = new TransactionManager(wal);
            var catalog = new Catalog();
            var transaction = manager.startTransaction();
            var name = new QualifiedName(List.of("people"));
            catalog.createTable(transaction, name, List.of(new ColumnDefinition("id", TypeName.BIGINT)));
            manager.recordWrite(transaction, new byte[] {42});
            transaction.enlist(new TransactionParticipant() {
                @Override public void prepareCommit(Transaction tx, long version) { }
                @Override public void commit(Transaction tx, long version) { throw new IllegalStateException("publish failure"); }
                @Override public void rollback(Transaction tx) { }
            });

            assertThrows(IllegalStateException.class, () -> manager.commit(transaction));
            assertEquals(0, wal.recoverCommittedTransactions().size());
            assertThrows(CatalogException.class, () -> catalog.lookupTable(manager.startTransaction(), name));
        }
    }
}
