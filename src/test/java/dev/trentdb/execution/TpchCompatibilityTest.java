package dev.trentdb.execution;

import dev.trentdb.ast.Statement;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.BoundStatement;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TpchCompatibilityTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void executesCanonicalTpchQ6SqlShapeFromGeneratedCsv() throws Exception {
        Fixture fixture = emptyFixture();
        String lineitemCsv = resourcePath("tpch/lineitem_q6_sf001.csv");

        QueryResult result = execute(fixture, """
                SELECT
                    sum(l_extendedprice * l_discount) AS revenue
                FROM
                    '%s'
                WHERE
                    l_shipdate >= CAST('1994-01-01' AS date)
                    AND l_shipdate < CAST('1995-01-01' AS date)
                    AND l_discount BETWEEN 0.05
                    AND 0.07
                    AND l_quantity < 24;
                """.formatted(sqlString(lineitemCsv)));

        assertEquals(List.of("revenue"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals(1193053.2253d, (Double) result.rows().getFirst().getFirst(), 0.000001d);
    }

    private QueryResult execute(Fixture fixture, String sql) {
        Statement statement = parser.parse(sql);
        BoundStatement bound = new Binder(fixture.catalog()).bind(fixture.transaction(), statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);
        return new QueryExecutor(fixture.storageManager()).execute(logical);
    }

    private Fixture emptyFixture() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        StorageManager storageManager = new StorageManager();
        return new Fixture(catalog, transaction, storageManager);
    }

    private String resourcePath(String name) throws URISyntaxException {
        URL resource = getClass().getClassLoader().getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Missing test resource: " + name);
        }
        return Path.of(resource.toURI()).toString();
    }

    private String sqlString(String value) {
        return value.replace("'", "''");
    }

    private record Fixture(Catalog catalog, Transaction transaction, StorageManager storageManager) {
    }
}
