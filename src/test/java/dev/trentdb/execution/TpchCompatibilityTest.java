package dev.trentdb.execution;

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
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TpchCompatibilityTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void executesCanonicalTpchQ6SqlShape() {
        Fixture fixture = lineitemFixture();

        QueryResult result = execute(fixture, """
                SELECT
                    sum(l_extendedprice * l_discount) AS revenue
                FROM
                    lineitem
                WHERE
                    l_shipdate >= CAST('1994-01-01' AS date)
                    AND l_shipdate < CAST('1995-01-01' AS date)
                    AND l_discount BETWEEN 0.05
                    AND 0.07
                    AND l_quantity < 24;
                """);

        assertEquals(List.of("revenue"), result.columns());
        assertEquals(List.of(List.of(19.0d)), result.rows());
    }

    private QueryResult execute(Fixture fixture, String sql) {
        Statement statement = parser.parse(sql);
        BoundStatement bound = new Binder(fixture.catalog()).bind(fixture.transaction(), statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);
        return new QueryExecutor(fixture.storageManager()).execute(logical);
    }

    private Fixture lineitemFixture() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("lineitem")),
                List.of(
                        new ColumnDefinition("l_shipdate", TypeName.DATE),
                        new ColumnDefinition("l_discount", TypeName.DOUBLE),
                        new ColumnDefinition("l_quantity", TypeName.BIGINT),
                        new ColumnDefinition("l_extendedprice", TypeName.DOUBLE)
                )
        );

        StorageManager storageManager = new StorageManager();
        InMemoryTableStorage storage = storageManager.createTable(table);
        storage.appendRow(List.of(LocalDate.parse("1994-01-01"), 0.05d, 10L, 100.0d));
        storage.appendRow(List.of(LocalDate.parse("1994-06-15"), 0.07d, 23L, 200.0d));
        storage.appendRow(List.of(LocalDate.parse("1994-12-31"), 0.06d, 24L, 300.0d));
        storage.appendRow(List.of(LocalDate.parse("1995-01-01"), 0.06d, 10L, 400.0d));
        storage.appendRow(List.of(LocalDate.parse("1994-07-01"), 0.04d, 10L, 500.0d));

        return new Fixture(catalog, transaction, storageManager);
    }

    private record Fixture(Catalog catalog, Transaction transaction, StorageManager storageManager) {
    }
}
