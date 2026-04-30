package dev.trentdb.catalog;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.types.LogicalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogTest {
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void createsTableInDefaultSchema() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();

        var table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );

        assertEquals(CatalogEntryType.TABLE, table.type());
        assertEquals("public", table.schema().name());
        assertEquals("people", table.name());
        assertEquals(2, table.columns().size());
        assertEquals(LogicalType.BIGINT, table.lookupColumn("id").logicalType());
        assertEquals(LogicalType.TEXT, table.lookupColumn("name").logicalType());
        assertEquals(1, table.lookupColumn("name").ordinal());
        assertSame(table, catalog.lookupTable(transaction, new QualifiedName(List.of("people"))));
    }

    @Test
    void createsTableInExplicitSchema() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        catalog.createSchema(transaction, "analytics");

        var table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("analytics", "events")),
                List.of(new ColumnDefinition("payload", TypeName.TEXT))
        );

        assertEquals("analytics", table.schema().name());
        assertSame(table, catalog.lookupTable(transaction, new QualifiedName(List.of("analytics", "events"))));
    }

    @Test
    void catalogOperationsRequireTransaction() {
        var catalog = new Catalog();

        var error = assertThrows(IllegalArgumentException.class, () -> catalog.lookupSchema(null, "public"));
        assertEquals("Catalog operation requires a transaction", error.getMessage());
    }

    @Test
    void transactionCarriesReadSnapshot() {
        var transaction = transactionManager.startTransaction();

        assertEquals(transaction.id(), transaction.snapshot().transactionId());
    }

    @Test
    void mapsAstTypeNamesToLogicalTypes() {
        assertEquals(LogicalType.INTEGER, LogicalType.from(TypeName.INT));
        assertEquals(LogicalType.BIGINT, LogicalType.from(TypeName.BIGINT));
        assertEquals(LogicalType.DOUBLE, LogicalType.from(TypeName.DOUBLE));
        assertEquals(LogicalType.BOOLEAN, LogicalType.from(TypeName.BOOLEAN));
        assertEquals(LogicalType.TEXT, LogicalType.from(TypeName.TEXT));
    }

    @Test
    void rejectsDuplicateTables() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        var columns = List.of(new ColumnDefinition("id", TypeName.BIGINT));
        var name = new QualifiedName(List.of("people"));

        catalog.createTable(transaction, name, columns);

        var error = assertThrows(CatalogException.class, () -> catalog.createTable(transaction, name, columns));
        assertEquals("Table already exists: people", error.getMessage());
    }

    @Test
    void rejectsMissingTableLookup() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();

        var error = assertThrows(CatalogException.class, () -> catalog.lookupTable(transaction, new QualifiedName(List.of("missing"))));
        assertEquals("Table not found: missing", error.getMessage());
    }

    @Test
    void rejectsDuplicateColumns() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();

        var error = assertThrows(CatalogException.class, () -> catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("id", TypeName.TEXT)
                )
        ));
        assertEquals("Column already exists: id", error.getMessage());
    }

    @Test
    void rejectsMissingColumnLookup() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        var table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(new ColumnDefinition("id", TypeName.BIGINT))
        );

        var error = assertThrows(CatalogException.class, () -> table.lookupColumn("missing"));
        assertEquals("Column not found: missing", error.getMessage());
    }
}
