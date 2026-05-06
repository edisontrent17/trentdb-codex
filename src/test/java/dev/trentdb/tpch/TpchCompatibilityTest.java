package dev.trentdb.tpch;

import dev.trentdb.ast.Statement;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.QueryExecutor;
import dev.trentdb.execution.QueryResult;
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
import java.time.LocalDate;
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
                    l_shipdate <= CAST('1998-09-02' AS date)
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

    @Test
    void executesTpchQ12FromGeneratedCsv() throws Exception {
        Fixture fixture = emptyFixture();
        String lineitemCsv = resourcePath("tpch/lineitem_q12_q14_sf001.csv");
        String ordersCsv = resourcePath("tpch/orders_q12_sf001.csv");

        QueryResult result = execute(fixture, """
                SELECT
                    l_shipmode,
                    sum(CASE WHEN o_orderpriority = '1-URGENT' OR o_orderpriority = '2-HIGH' THEN 1 ELSE 0 END) AS high_line_count,
                    sum(CASE WHEN o_orderpriority <> '1-URGENT' AND o_orderpriority <> '2-HIGH' THEN 1 ELSE 0 END) AS low_line_count
                FROM
                    '%s' l
                JOIN
                    '%s' o
                ON
                    o.o_orderkey = l.l_orderkey
                WHERE
                    l_shipmode IN ('MAIL', 'SHIP')
                    AND l_commitdate < l_receiptdate
                    AND l_shipdate < l_commitdate
                    AND l_receiptdate >= DATE '1994-01-01'
                    AND l_receiptdate < DATE '1994-01-01' + INTERVAL '1' YEAR
                GROUP BY
                    l_shipmode
                ORDER BY
                    l_shipmode;
                """.formatted(sqlString(lineitemCsv), sqlString(ordersCsv)));

        assertEquals(List.of("l_shipmode", "high_line_count", "low_line_count"), result.columns());
        assertEquals(List.of(
                List.of("MAIL", 64L, 86L),
                List.of("SHIP", 61L, 96L)
        ), result.rows());
    }

    @Test
    void executesTpchQ14FromGeneratedCsv() throws Exception {
        Fixture fixture = emptyFixture();
        String lineitemCsv = resourcePath("tpch/lineitem_q12_q14_sf001.csv");
        String partCsv = resourcePath("tpch/part_q14_sf001.csv");

        QueryResult result = execute(fixture, """
                SELECT
                    100.00 * sum(CASE WHEN p_type LIKE 'PROMO%%' THEN l_extendedprice * (1 - l_discount) ELSE 0 END)
                    / sum(l_extendedprice * (1 - l_discount)) AS promo_revenue
                FROM
                    '%s' l
                JOIN
                    '%s' p
                ON
                    l.l_partkey = p.p_partkey
                WHERE
                    l_shipdate >= DATE '1995-09-01'
                    AND l_shipdate < DATE '1995-09-01' + INTERVAL '1' MONTH;
                """.formatted(sqlString(lineitemCsv), sqlString(partCsv)));

        assertEquals(List.of("promo_revenue"), result.columns());
        assertEquals(1, result.rows().size());
        assertDoubleEquals(15.486545812284078d, (Double) result.rows().getFirst().getFirst());
    }

    @Test
    void executesTpchQ3FromGeneratedCsv() throws Exception {
        Fixture fixture = emptyFixture();
        String customerCsv = resourcePath("tpch/customer_q3_sf001.csv");
        String ordersCsv = resourcePath("tpch/orders_q3_sf001.csv");
        String lineitemCsv = resourcePath("tpch/lineitem_q12_q14_sf001.csv");

        QueryResult result = execute(fixture, """
                SELECT
                    l_orderkey,
                    sum(l_extendedprice * (1 - l_discount)) AS revenue,
                    o_orderdate,
                    o_shippriority
                FROM
                    '%s' c
                JOIN
                    '%s' o
                ON
                    c.c_custkey = o.o_custkey
                JOIN
                    '%s' l
                ON
                    l.l_orderkey = o.o_orderkey
                WHERE
                    c_mktsegment = 'BUILDING'
                    AND o_orderdate < DATE '1995-03-15'
                    AND l_shipdate > DATE '1995-03-15'
                GROUP BY
                    l_orderkey,
                    o_orderdate,
                    o_shippriority
                ORDER BY
                    revenue DESC,
                    o_orderdate
                LIMIT 10;
                """.formatted(sqlString(customerCsv), sqlString(ordersCsv), sqlString(lineitemCsv)));

        assertEquals(List.of("l_orderkey", "revenue", "o_orderdate", "o_shippriority"), result.columns());
        assertEquals(10, result.rows().size());
        assertQ3Row(result.rows().get(0), 47714L, 267010.5894d, LocalDate.of(1995, 3, 11), 0L);
        assertQ3Row(result.rows().get(1), 22276L, 266351.55620000005d, LocalDate.of(1995, 1, 29), 0L);
        assertQ3Row(result.rows().get(2), 32965L, 263768.3414d, LocalDate.of(1995, 2, 25), 0L);
        assertQ3Row(result.rows().get(3), 21956L, 254541.1285d, LocalDate.of(1995, 2, 2), 0L);
        assertQ3Row(result.rows().get(4), 1637L, 243512.79809999999d, LocalDate.of(1995, 2, 8), 0L);
        assertQ3Row(result.rows().get(5), 10916L, 241320.08140000002d, LocalDate.of(1995, 3, 11), 0L);
        assertQ3Row(result.rows().get(6), 30497L, 208566.69689999998d, LocalDate.of(1995, 2, 7), 0L);
        assertQ3Row(result.rows().get(7), 450L, 205447.42320000002d, LocalDate.of(1995, 3, 5), 0L);
        assertQ3Row(result.rows().get(8), 47204L, 204478.5213d, LocalDate.of(1995, 3, 13), 0L);
        assertQ3Row(result.rows().get(9), 9696L, 201502.21879999997d, LocalDate.of(1995, 2, 20), 0L);
    }

    @Test
    void executesTpchQ19FromGeneratedCsv() throws Exception {
        Fixture fixture = emptyFixture();
        String lineitemCsv = resourcePath("tpch/lineitem_q19_sf001.csv");
        String partCsv = resourcePath("tpch/part_q19_sf001.csv");

        QueryResult result = execute(fixture, """
                SELECT
                    sum(l_extendedprice * (1 - l_discount)) AS revenue
                FROM
                    '%s' l
                JOIN
                    '%s' p
                ON
                    p.p_partkey = l.l_partkey
                WHERE
                    (
                        p_brand = 'Brand#12'
                        AND p_container IN ('SM CASE', 'SM BOX', 'SM PACK', 'SM PKG')
                        AND l_quantity >= 1
                        AND l_quantity <= 11
                        AND p_size BETWEEN 1 AND 5
                        AND l_shipmode IN ('AIR', 'AIR REG')
                        AND l_shipinstruct = 'DELIVER IN PERSON'
                    )
                    OR
                    (
                        p_brand = 'Brand#23'
                        AND p_container IN ('MED BAG', 'MED BOX', 'MED PKG', 'MED PACK')
                        AND l_quantity >= 10
                        AND l_quantity <= 20
                        AND p_size BETWEEN 1 AND 10
                        AND l_shipmode IN ('AIR', 'AIR REG')
                        AND l_shipinstruct = 'DELIVER IN PERSON'
                    )
                    OR
                    (
                        p_brand = 'Brand#34'
                        AND p_container IN ('LG CASE', 'LG BOX', 'LG PACK', 'LG PKG')
                        AND l_quantity >= 20
                        AND l_quantity <= 30
                        AND p_size BETWEEN 1 AND 15
                        AND l_shipmode IN ('AIR', 'AIR REG')
                        AND l_shipinstruct = 'DELIVER IN PERSON'
                    );
                """.formatted(sqlString(lineitemCsv), sqlString(partCsv)));

        assertEquals(List.of("revenue"), result.columns());
        assertEquals(1, result.rows().size());
        assertDoubleEquals(22923.028d, (Double) result.rows().getFirst().getFirst());
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

    private void assertQ3Row(
            List<Object> row,
            long orderKey,
            double revenue,
            LocalDate orderDate,
            long shipPriority
    ) {
        assertEquals(orderKey, row.get(0));
        assertDoubleEquals(revenue, (Double) row.get(1));
        assertEquals(orderDate, row.get(2));
        assertEquals(shipPriority, row.get(3));
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
