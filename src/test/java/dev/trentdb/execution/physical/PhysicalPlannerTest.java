package dev.trentdb.execution.physical;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
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
        var storageManager = new StorageManager();
        storageManager.createTable(table);

        var statement = parser.parse("SELECT id FROM people WHERE id = 1");
        var bound = new Binder(catalog).bind(transaction, statement);
        var logical = new LogicalPlanner().plan(bound);

        var pipeline = new PhysicalPlanner(storageManager).plan(logical);

        assertInstanceOf(PhysicalTableScan.class, pipeline.source());
        assertEquals(2, pipeline.operators().size());
        assertInstanceOf(PhysicalFilter.class, pipeline.operators().get(0));
        assertInstanceOf(PhysicalProjection.class, pipeline.operators().get(1));
        assertInstanceOf(PhysicalResultCollector.class, pipeline.sink());
    }
}
