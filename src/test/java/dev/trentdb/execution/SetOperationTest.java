package dev.trentdb.execution;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.SelectStatement;
import dev.trentdb.ast.SetOperation;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.BinderException;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetOperationTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactions = new TransactionManager();

    @Test
    void parserGivesIntersectHigherPrecedenceThanUnion() {
        SelectStatement select = assertInstanceOf(SelectStatement.class, parser.parse(
                "SELECT id FROM people UNION SELECT id FROM people INTERSECT SELECT id FROM people"));

        assertEquals(SetOperation.UNION, select.setOperation());
        assertEquals(SetOperation.INTERSECT, select.right().setOperation());
    }

    @Test
    void binderRejectsWidthMismatchAndAlignsNumericTypes() {
        Fixture fixture = fixture();

        assertThrows(BinderException.class, () -> bind(fixture,
                "SELECT id, name FROM people UNION SELECT id FROM people"));

        BoundSelectStatement bound = bind(fixture, "SELECT value FROM small UNION SELECT id FROM people");
        assertTrue(bound.isCompound());
        assertInstanceOf(BoundCastExpression.class, bound.left().selectList().getFirst());
    }

    @Test
    void executesDistinctAndBagSetSemanticsWithNullTuples() {
        Fixture fixture = fixture();

        assertEquals(List.of(List.of(1L), List.of(1L)), execute(fixture,
                "SELECT id FROM people WHERE id = 1 UNION ALL SELECT id FROM people WHERE id = 1").rows());
        assertEquals(List.of(List.of(1L)), execute(fixture,
                "SELECT id FROM people WHERE id = 1 UNION SELECT id FROM people WHERE id = 1").rows());
        assertEquals(List.of(List.of(1L)), execute(fixture,
                "SELECT id FROM people EXCEPT SELECT id FROM people WHERE id = 2").rows());
        assertEquals(List.of(java.util.Collections.singletonList(null)), execute(fixture,
                "SELECT value FROM nullable_values INTERSECT SELECT value FROM nullable_values WHERE value IS NULL").rows());
    }

    @Test
    void preservesOutputSchemaForEmptyIntersectAndExcept() {
        Fixture fixture = fixture();
        QueryResult intersect = execute(fixture,
                "SELECT id FROM people WHERE id = 1 INTERSECT SELECT id FROM people WHERE id = 2");
        QueryResult except = execute(fixture,
                "SELECT id FROM people WHERE id = 1 EXCEPT SELECT id FROM people WHERE id = 1");

        assertEquals(List.of("id"), intersect.columns());
        assertEquals(List.of(), intersect.rows());
        assertEquals(List.of("id"), except.columns());
        assertEquals(List.of(), except.rows());
    }

    private BoundSelectStatement bind(Fixture fixture, String sql) {
        return assertInstanceOf(BoundSelectStatement.class,
                new Binder(fixture.catalog()).bind(fixture.transaction(), parser.parse(sql)));
    }

    private QueryResult execute(Fixture fixture, String sql) {
        Statement statement = parser.parse(sql);
        var bound = new Binder(fixture.catalog()).bind(fixture.transaction(), statement);
        return new QueryExecutor(fixture.storage()).execute(new LogicalPlanner().plan(bound));
    }

    private Fixture fixture() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactions.startTransaction();
        StorageManager storage = new StorageManager();
        TableCatalogEntry people = catalog.createTable(transaction, new QualifiedName(List.of("people")), List.of(
                new ColumnDefinition("id", TypeName.BIGINT), new ColumnDefinition("name", TypeName.TEXT)));
        InMemoryTableStorage peopleStorage = storage.createTable(people);
        peopleStorage.appendRow(List.of(1L, "one"));
        peopleStorage.appendRow(List.of(2L, "two"));
        TableCatalogEntry small = catalog.createTable(transaction, new QualifiedName(List.of("small")),
                List.of(new ColumnDefinition("value", TypeName.INT)));
        storage.createTable(small).appendRow(List.of(1));
        TableCatalogEntry nullable = catalog.createTable(transaction, new QualifiedName(List.of("nullable_values")),
                List.of(new ColumnDefinition("value", TypeName.TEXT)));
        storage.createTable(nullable).appendRow(java.util.Collections.singletonList(null));
        return new Fixture(catalog, transaction, storage);
    }

    private record Fixture(Catalog catalog, Transaction transaction, StorageManager storage) {
    }
}
