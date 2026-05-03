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
                    l_shipdate >= DATE '1994-01-01'
                    AND l_shipdate < DATE '1995-01-01'
                    AND l_discount BETWEEN 0.05
                    AND 0.07
                    AND l_quantity < 24;
                """.formatted(sqlString(lineitemCsv)));

        assertEquals(List.of("revenue"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals(1193053.2253d, (Double) result.rows().getFirst().getFirst(), 0.000001d);
    }

    @Test
    void executesCanonicalTpchQ1SqlShapeFromGeneratedCsv() throws Exception {
        Fixture fixture = emptyFixture();
        String lineitemCsv = resourcePath("tpch/lineitem_q1_sf001.csv");

        QueryResult result = execute(fixture, """
                SELECT
                    l_returnflag,
                    l_linestatus,
                    sum(l_quantity) AS sum_qty,
                    sum(l_extendedprice) AS sum_base_price,
                    sum(l_extendedprice * (1 - l_discount)) AS sum_disc_price,
                    sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) AS sum_charge,
                    avg(l_quantity) AS avg_qty,
                    avg(l_extendedprice) AS avg_price,
                    avg(l_discount) AS avg_disc,
                    count(*) AS count_order
                FROM
                    '%s'
                WHERE
                    l_shipdate <= DATE '1998-12-01' - INTERVAL '90' DAY
                GROUP BY
                    l_returnflag,
                    l_linestatus
                ORDER BY
                    l_returnflag,
                    l_linestatus;
                """.formatted(sqlString(lineitemCsv)));

        assertEquals(List.of(
                "l_returnflag",
                "l_linestatus",
                "sum_qty",
                "sum_base_price",
                "sum_disc_price",
                "sum_charge",
                "avg_qty",
                "avg_price",
                "avg_disc",
                "count_order"
        ), result.columns());
        assertQ1Row(result.rows().get(0), "A", "F", 380456.0d, 532348211.65d, 505822441.4861d,
                526165934.000839d, 25.575154611454693d, 35785.70930693735d,
                0.05008133906964238d, 14876L);
        assertQ1Row(result.rows().get(1), "N", "F", 8971.0d, 12384801.37d, 11798257.2080d,
                12282485.056933d, 25.778735632183906d, 35588.50968390804d,
                0.047758620689655175d, 348L);
        assertQ1Row(result.rows().get(2), "N", "O", 742802.0d, 1041502841.45d, 989737518.6346d,
                1029418531.523350d, 25.45498783454988d, 35691.129209074395d,
                0.04993111956409993d, 29181L);
        assertQ1Row(result.rows().get(3), "R", "F", 381449.0d, 534594445.35d, 507996454.4067d,
                528524219.358903d, 25.597168165346933d, 35874.00653268018d,
                0.049827539927526504d, 14902L);
    }

    private QueryResult execute(Fixture fixture, String sql) {
        Statement statement = parser.parse(sql);
        BoundStatement bound = new Binder(fixture.catalog()).bind(fixture.transaction(), statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);
        return new QueryExecutor(fixture.storageManager()).execute(logical);
    }

    private void assertQ1Row(
            List<Object> row,
            String returnFlag,
            String lineStatus,
            double sumQuantity,
            double sumBasePrice,
            double sumDiscountedPrice,
            double sumCharge,
            double averageQuantity,
            double averagePrice,
            double averageDiscount,
            long countOrder
    ) {
        assertEquals(returnFlag, row.get(0));
        assertEquals(lineStatus, row.get(1));
        assertDoubleEquals(sumQuantity, (Double) row.get(2));
        assertDoubleEquals(sumBasePrice, (Double) row.get(3));
        assertDoubleEquals(sumDiscountedPrice, (Double) row.get(4));
        assertDoubleEquals(sumCharge, (Double) row.get(5));
        assertDoubleEquals(averageQuantity, (Double) row.get(6));
        assertDoubleEquals(averagePrice, (Double) row.get(7));
        assertDoubleEquals(averageDiscount, (Double) row.get(8));
        assertEquals(countOrder, row.get(9));
    }

    private void assertDoubleEquals(double expected, double actual) {
        assertEquals(expected, actual, Math.max(0.000001d, Math.abs(expected) * 0.000000000001d));
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
