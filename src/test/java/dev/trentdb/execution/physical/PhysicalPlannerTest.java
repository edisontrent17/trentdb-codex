package dev.trentdb.execution.physical;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.BoundStatement;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PhysicalPlannerTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void plansSourceOperatorsAndSink() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        StorageManager storageManager = new StorageManager();
        storageManager.createTable(table);

        Statement statement = parser.parse("SELECT id FROM people WHERE id = 1 ORDER BY name");
        BoundStatement bound = new Binder(catalog).bind(transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        Pipeline pipeline = new PhysicalPlanner(storageManager).plan(logical);

        assertInstanceOf(PhysicalTableScan.class, pipeline.source());
        assertEquals(PhysicalOperatorType.TABLE_SCAN, pipeline.source().type());
        assertEquals(3, pipeline.operators().size());
        assertInstanceOf(PhysicalFilter.class, pipeline.operators().get(0));
        assertEquals(PhysicalOperatorType.FILTER, pipeline.operators().get(0).type());
        assertInstanceOf(PhysicalOrder.class, pipeline.operators().get(1));
        assertEquals(PhysicalOperatorType.ORDER_BY, pipeline.operators().get(1).type());
        assertInstanceOf(PhysicalProjection.class, pipeline.operators().get(2));
        assertEquals(PhysicalOperatorType.PROJECTION, pipeline.operators().get(2).type());
        assertInstanceOf(PhysicalResultCollector.class, pipeline.sink());
        assertEquals(PhysicalOperatorType.RESULT_COLLECTOR, pipeline.sink().type());
    }

    @Test
    void plansEquiJoinAsHashOperator() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry people = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        TableCatalogEntry orders = catalog.createTable(
                transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );
        StorageManager storageManager = new StorageManager();
        storageManager.createTable(people);
        storageManager.createTable(orders);

        Statement statement = parser.parse("""
                SELECT p.name
                FROM people p
                JOIN orders o ON p.id = o.person_id
                WHERE o.total > 10
                ORDER BY p.name
                """);
        BoundStatement bound = new Binder(catalog).bind(transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        Pipeline pipeline = new PhysicalPlanner(storageManager).plan(logical);

        assertInstanceOf(PhysicalTableScan.class, pipeline.source());
        assertEquals(PhysicalOperatorType.TABLE_SCAN, pipeline.source().type());
        assertEquals(3, pipeline.operators().size());
        PhysicalHashJoin join = assertInstanceOf(PhysicalHashJoin.class, pipeline.operators().get(0));
        assertEquals(PhysicalOperatorType.HASH_JOIN, join.type());
        assertEquals("orders", join.right().table().name());
        assertEquals(0, join.leftKeyOrdinal());
        assertEquals(0, join.rightKeyOrdinal());
        assertInstanceOf(PhysicalOrder.class, pipeline.operators().get(1));
        assertEquals(PhysicalOperatorType.ORDER_BY, pipeline.operators().get(1).type());
        assertInstanceOf(PhysicalProjection.class, pipeline.operators().get(2));
        assertEquals(PhysicalOperatorType.PROJECTION, pipeline.operators().get(2).type());
        assertInstanceOf(PhysicalResultCollector.class, pipeline.sink());
        assertEquals(PhysicalOperatorType.RESULT_COLLECTOR, pipeline.sink().type());
    }

    @Test
    void plansNonEquiJoinAsNestedLoopOperator() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry people = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        TableCatalogEntry orders = catalog.createTable(
                transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );
        StorageManager storageManager = new StorageManager();
        storageManager.createTable(people);
        storageManager.createTable(orders);

        Statement statement = parser.parse("""
                SELECT p.name
                FROM people p
                JOIN orders o ON p.id > o.person_id
                WHERE o.total > 10
                ORDER BY p.name
                """);
        BoundStatement bound = new Binder(catalog).bind(transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        Pipeline pipeline = new PhysicalPlanner(storageManager).plan(logical);

        assertInstanceOf(PhysicalTableScan.class, pipeline.source());
        assertEquals(PhysicalOperatorType.TABLE_SCAN, pipeline.source().type());
        assertInstanceOf(PhysicalNestedLoopJoin.class, pipeline.operators().getFirst());
        assertEquals(PhysicalOperatorType.NESTED_LOOP_JOIN, pipeline.operators().getFirst().type());
    }

    @Test
    void pushesResidualFilterIntoHashJoinWhenPredicateReferencesBothSides() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry people = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        TableCatalogEntry orders = catalog.createTable(
                transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );
        StorageManager storageManager = new StorageManager();
        storageManager.createTable(people);
        storageManager.createTable(orders);

        Statement statement = parser.parse("""
                SELECT p.name
                FROM people p
                JOIN orders o ON p.id = o.person_id
                WHERE o.total > 10 AND p.id + o.total > 20
                ORDER BY p.name
                """);
        BoundStatement bound = new Binder(catalog).bind(transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        Pipeline pipeline = new PhysicalPlanner(storageManager).plan(logical);

        assertInstanceOf(PhysicalTableScan.class, pipeline.source());
        assertEquals(3, pipeline.operators().size());
        PhysicalHashJoin join = assertInstanceOf(PhysicalHashJoin.class, pipeline.operators().get(0));
        assertEquals(PhysicalOperatorType.HASH_JOIN, join.type());
        assertInstanceOf(PhysicalOrder.class, pipeline.operators().get(1));
        assertInstanceOf(PhysicalProjection.class, pipeline.operators().get(2));
    }
}
