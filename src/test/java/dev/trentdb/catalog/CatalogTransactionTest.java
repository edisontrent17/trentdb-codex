package dev.trentdb.catalog;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.transaction.TransactionParticipant;
import dev.trentdb.transaction.TransactionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogTransactionTest {
    private static final QualifiedName PEOPLE = new QualifiedName(List.of("people"));
    private static final List<ColumnDefinition> PEOPLE_COLUMNS = List.of(new ColumnDefinition("id", TypeName.BIGINT));

    @Test
    void createIsPrivateUntilCommitAndVisibleToItsWriter() {
        var transactions = new TransactionManager();
        var catalog = new Catalog();
        var writer = transactions.startTransaction();
        var readerWithEarlierSnapshot = transactions.startTransaction();

        var created = catalog.createTable(writer, PEOPLE, PEOPLE_COLUMNS);

        assertSame(created, catalog.lookupTable(writer, PEOPLE));
        assertThrows(CatalogException.class, () -> catalog.lookupTable(readerWithEarlierSnapshot, PEOPLE));

        transactions.commit(writer);

        assertEquals(TransactionState.COMMITTED, writer.state());
        assertThrows(CatalogException.class, () -> catalog.lookupTable(readerWithEarlierSnapshot, PEOPLE));
        var futureReader = transactions.startTransaction();
        assertSame(created, catalog.lookupTable(futureReader, PEOPLE));
    }

    @Test
    void rollbackDiscardsStagedCatalogChanges() {
        var transactions = new TransactionManager();
        var catalog = new Catalog();
        var writer = transactions.startTransaction();

        catalog.createTable(writer, PEOPLE, PEOPLE_COLUMNS);
        transactions.rollback(writer);

        assertEquals(TransactionState.ROLLED_BACK, writer.state());
        assertThrows(CatalogException.class, () -> catalog.lookupTable(transactions.startTransaction(), PEOPLE));
    }

    @Test
    void dropIsSnapshotSafeAndOnlyAffectsFutureReaders() {
        var transactions = new TransactionManager();
        var catalog = new Catalog();
        var creator = transactions.startTransaction();
        var original = catalog.createTable(creator, PEOPLE, PEOPLE_COLUMNS);
        transactions.commit(creator);

        var reader = transactions.startTransaction();
        var dropper = transactions.startTransaction();
        catalog.dropTable(dropper, PEOPLE);
        assertThrows(CatalogException.class, () -> catalog.lookupTable(dropper, PEOPLE));
        transactions.commit(dropper);

        assertSame(original, catalog.lookupTable(reader, PEOPLE));
        assertThrows(CatalogException.class, () -> catalog.lookupTable(transactions.startTransaction(), PEOPLE));
    }

    @Test
    void concurrentCreateConflictRollsBackTheLosingTransaction() {
        var transactions = new TransactionManager();
        var catalog = new Catalog();
        var firstWriter = transactions.startTransaction();
        var secondWriter = transactions.startTransaction();
        var first = catalog.createTable(firstWriter, PEOPLE, PEOPLE_COLUMNS);
        catalog.createTable(secondWriter, PEOPLE, PEOPLE_COLUMNS);

        transactions.commit(firstWriter);

        assertThrows(CatalogException.class, () -> transactions.commit(secondWriter));
        assertEquals(TransactionState.ROLLED_BACK, secondWriter.state());
        assertSame(first, catalog.lookupTable(transactions.startTransaction(), PEOPLE));
    }

    @Test
    void participantCommitFailureRestoresCatalogVisibility() {
        var transactions = new TransactionManager();
        var catalog = new Catalog();
        var writer = transactions.startTransaction();
        catalog.createTable(writer, PEOPLE, PEOPLE_COLUMNS);
        writer.enlist(new TransactionParticipant() {
            @Override
            public void prepareCommit(Transaction transaction, long commitVersion) {
            }

            @Override
            public void commit(Transaction transaction, long commitVersion) {
                throw new IllegalStateException("participant commit failure");
            }

            @Override
            public void rollback(Transaction transaction) {
            }
        });

        var failure = assertThrows(IllegalStateException.class, () -> transactions.commit(writer));

        assertEquals("participant commit failure", failure.getMessage());
        assertEquals(TransactionState.ROLLED_BACK, writer.state());
        assertEquals(0, transactions.committedCatalogVersion());
        assertThrows(CatalogException.class, () -> catalog.lookupTable(transactions.startTransaction(), PEOPLE));
    }

    @Test
    void rollbackFailureDoesNotMaskCommitFailureOrLeaveTransactionActive() {
        var transactions = new TransactionManager();
        var writer = transactions.startTransaction();
        writer.enlist(new TransactionParticipant() {
            @Override
            public void prepareCommit(Transaction transaction, long commitVersion) {
                throw new IllegalStateException("prepare failure");
            }

            @Override
            public void commit(Transaction transaction, long commitVersion) {
            }

            @Override
            public void rollback(Transaction transaction) {
                throw new IllegalStateException("rollback failure");
            }
        });

        var failure = assertThrows(IllegalStateException.class, () -> transactions.commit(writer));

        assertEquals("prepare failure", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("rollback failure", failure.getSuppressed()[0].getMessage());
        assertEquals(TransactionState.ROLLED_BACK, writer.state());
    }
}
