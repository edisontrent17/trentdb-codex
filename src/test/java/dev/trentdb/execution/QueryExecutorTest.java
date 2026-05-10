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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryExecutorTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void executesSelectStar() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT * FROM people");

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(
                List.of(1L, "Alice"),
                List.of(2L, "Bob")
        ), result.rows());
    }

    @Test
    void executesProjection() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name, id FROM people");

        assertEquals(List.of("name", "id"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 1L),
                List.of("Bob", 2L)
        ), result.rows());
    }

    @Test
    void executesProjectionAliases() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name AS person_name, lower(name) lowered FROM people LIMIT 1");

        assertEquals(List.of("person_name", "lowered"), result.columns());
        assertEquals(List.of(List.of("Alice", "alice")), result.rows());
    }

    @Test
    void executesArithmeticProjection() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id + 1 AS next_id, id * 2 doubled FROM people");

        assertEquals(List.of("next_id", "doubled"), result.columns());
        assertEquals(List.of(
                List.of(2L, 2L),
                List.of(3L, 4L)
        ), result.rows());
    }

    @Test
    void executesDivisionAsDouble() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id / 2 AS half FROM people LIMIT 1");

        assertEquals(List.of("half"), result.columns());
        assertEquals(List.of(List.of(0.5d)), result.rows());
    }

    @Test
    void executesFilter() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE id = 1");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void executesArithmeticFilter() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name FROM people WHERE id + 1 = 2");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void executesInPredicate() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name FROM people WHERE id IN (2, 3)");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Bob")), result.rows());
    }

    @Test
    void executesNotInPredicate() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name FROM people WHERE id NOT IN (1, 3)");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Bob")), result.rows());
    }

    @Test
    void executesInSubqueryPredicate() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT name
                FROM people
                WHERE id IN (SELECT person_id FROM orders WHERE total > 20)
                ORDER BY name
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Bob")), result.rows());
    }

    @Test
    void executesNotInSubqueryPredicate() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT name
                FROM people
                WHERE id NOT IN (SELECT person_id FROM orders WHERE total > 20)
                ORDER BY name
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void executesUncorrelatedExistsPredicate() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT name
                FROM people
                WHERE EXISTS (SELECT * FROM orders WHERE total > 20)
                ORDER BY name
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice"), List.of("Bob")), result.rows());
    }

    @Test
    void executesCorrelatedExistsPredicateAsMarkJoin() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT name
                FROM people p
                WHERE EXISTS (
                    SELECT *
                    FROM orders o
                    WHERE o.person_id = p.id
                    AND o.total > 15
                )
                ORDER BY name
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice"), List.of("Bob")), result.rows());
    }

    @Test
    void executesLikePredicate() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name FROM people WHERE name LIKE 'A%'");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void executesNotLikePredicate() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name FROM people WHERE name NOT LIKE '_lice'");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Bob")), result.rows());
    }

    @Test
    void executesSearchedCaseExpression() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(
                fixture,
                "SELECT CASE WHEN id = 1 THEN 'one' WHEN id = 2 THEN 'two' ELSE 'other' END AS label FROM people"
        );

        assertEquals(List.of("label"), result.columns());
        assertEquals(List.of(List.of("one"), List.of("two")), result.rows());
    }

    @Test
    void searchedCaseUsesNullElseWhenOmitted() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT CASE WHEN id = 1 THEN 10 END AS maybe_ten FROM people");

        assertEquals(List.of("maybe_ten"), result.columns());
        assertEquals(java.util.Arrays.asList(
                List.of(10L),
                java.util.Arrays.asList((Object) null)
        ), result.rows());
    }

    @Test
    void executesAggregateOverCaseExpression() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT sum(CASE WHEN name LIKE 'A%' THEN 1 ELSE 0 END) AS a_count FROM people");

        assertEquals(List.of("a_count"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void executesScalarSubqueryExpression() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                "SELECT name, (SELECT max(total) FROM orders) AS max_total FROM people ORDER BY id"
        );

        assertEquals(List.of("name", "max_total"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 40L),
                List.of("Bob", 40L)
        ), result.rows());
    }

    @Test
    void scalarSubqueryWithoutRowsReturnsNull() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                "SELECT (SELECT total FROM orders WHERE total > 100) AS maybe_total FROM people LIMIT 1"
        );

        assertEquals(List.of("maybe_total"), result.columns());
        assertEquals(List.of(java.util.Arrays.asList((Object) null)), result.rows());
    }

    @Test
    void scalarSubqueryRejectsMultipleRows() {
        Fixture fixture = peopleOrdersFixture();

        ExecutionException error = assertThrows(
                ExecutionException.class,
                () -> execute(fixture, "SELECT (SELECT total FROM orders) AS total FROM people LIMIT 1")
        );

        assertEquals("Scalar subquery returned more than one row", error.getMessage());
    }

    @Test
    void executesCorrelatedScalarAggregatePredicateAsSingleJoin() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT name
                FROM people p
                WHERE (SELECT count(*) FROM orders o WHERE o.person_id = p.id) = 0
                ORDER BY name
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Charlie")), result.rows());
    }

    @Test
    void explainsCorrelatedScalarAggregateAsSingleJoin() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                EXPLAIN SELECT name
                FROM people p
                WHERE (SELECT count(*) FROM orders o WHERE o.person_id = p.id) = 0
                """
        );

        String plan = (String) result.rows().getFirst().getFirst();
        assertPlanContains(plan, "DELIM_JOIN", "Join Type:", "SINGLE", "SINGLE_JOIN", "Subquery:", "SCALAR");
    }

    @Test
    void keepsUnsupportedCorrelatedScalarAggregateOnEvaluatorPath() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT name
                FROM people p
                WHERE (SELECT count(*) FROM orders o WHERE o.person_id > p.id) = 1
                ORDER BY name
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void correlatedScalarAggregateSingleJoinSupportsBigintMinKey() {
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
        InMemoryTableStorage peopleStorage = storageManager.createTable(people);
        peopleStorage.appendRow(List.of(Long.MIN_VALUE, "Alice"));
        peopleStorage.appendRow(List.of(1L, "Bob"));
        InMemoryTableStorage orderStorage = storageManager.createTable(orders);
        orderStorage.appendRow(List.of(Long.MIN_VALUE, 10L));

        QueryResult result = execute(
                new Fixture(catalog, transaction, storageManager),
                """
                SELECT name
                FROM people p
                WHERE (SELECT count(*) FROM orders o WHERE o.person_id = p.id) = 1
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void inPredicateWithNullCandidateUsesSqlThreeValuedLogic() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE name IN ('Alice', NULL)");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void notInPredicateWithNullCandidateUsesSqlThreeValuedLogic() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE name NOT IN ('Alice', NULL)");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void executesLimit() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT * FROM people LIMIT 1");

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(List.of(1L, "Alice")), result.rows());
    }

    @Test
    void executesZeroLimit() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT * FROM people LIMIT 0");

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void executesFilterBeforeLimit() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE id > 0 LIMIT 1");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void executesOrderByAscending() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people ORDER BY name ASC");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L), List.of(2L)), result.rows());
    }

    @Test
    void executesOrderByDescending() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people ORDER BY id DESC");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(2L), List.of(1L)), result.rows());
    }

    @Test
    void executesOrderByAlias() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name AS n FROM people ORDER BY n DESC");

        assertEquals(List.of("n"), result.columns());
        assertEquals(List.of(List.of("Bob"), List.of("Alice")), result.rows());
    }

    @Test
    void executesOrderBySelectListPosition() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name, id FROM people ORDER BY 2 DESC");

        assertEquals(List.of("name", "id"), result.columns());
        assertEquals(List.of(List.of("Bob", 2L), List.of("Alice", 1L)), result.rows());
    }

    @Test
    void executesOrderByExpressionOutsideProjection() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT name FROM people ORDER BY id DESC");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Bob"), List.of("Alice")), result.rows());
    }

    @Test
    void executesOrderBeforeLimit() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people ORDER BY id DESC LIMIT 1");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(2L)), result.rows());
    }

    @Test
    void executesInnerJoin() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT p.name, o.total
                FROM people p
                JOIN orders o ON p.id = o.person_id
                ORDER BY o.total
                """
        );

        assertEquals(List.of("name", "total"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 10L),
                List.of("Alice", 20L),
                List.of("Bob", 30L)
        ), result.rows());
    }

    @Test
    void executesInnerJoinWithWhereFilter() {
        Fixture fixture = peopleOrdersFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT p.name
                FROM people p
                JOIN orders o ON p.id = o.person_id
                WHERE o.total >= 20
                ORDER BY p.name, o.total
                """
        );

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(
                List.of("Alice"),
                List.of("Bob")
        ), result.rows());
    }

    @Test
    void executesMultipleInnerJoins() {
        Fixture fixture = customersOrdersLineitemsFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT c.name, l.amount
                FROM customers c
                JOIN orders o ON c.cust_id = o.cust_id
                JOIN lineitems l ON o.order_id = l.order_id
                WHERE l.amount >= 20
                ORDER BY c.name, l.amount
                """
        );

        assertEquals(List.of("name", "amount"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 20L),
                List.of("Bob", 30L)
        ), result.rows());
    }

    @Test
    void executesLeftJoinWithOnResidualPredicate() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT p.name, count(o.total) AS matching_orders
                FROM people p
                LEFT JOIN orders o ON p.id = o.person_id AND o.total > 20
                GROUP BY p.name
                ORDER BY p.name
                """
        );

        assertEquals(List.of("name", "matching_orders"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 0L),
                List.of("Bob", 1L),
                List.of("Charlie", 0L)
        ), result.rows());
    }

    @Test
    void executesDerivedTableWithColumnAliases() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                SELECT c_count, count(*) AS custdist
                FROM (
                    SELECT p.id, count(o.total) AS order_count
                    FROM people p
                    LEFT JOIN orders o ON p.id = o.person_id
                    GROUP BY p.id
                ) AS c_orders (c_custkey, c_count)
                GROUP BY c_count
                ORDER BY c_count
                """
        );

        assertEquals(List.of("c_count", "custdist"), result.columns());
        assertEquals(List.of(
                List.of(0L, 1L),
                List.of(1L, 1L),
                List.of(2L, 1L)
        ), result.rows());
    }

    @Test
    void executesCommonTableExpressionReferencedTwice() {
        Fixture fixture = peopleOrdersWithUnmatchedFixture();

        QueryResult result = execute(
                fixture,
                """
                WITH order_totals AS (
                    SELECT person_id AS customer_id, sum(total) AS total_spend
                    FROM orders
                    GROUP BY customer_id
                )
                SELECT name, total_spend
                FROM order_totals
                JOIN people p ON p.id = customer_id
                WHERE total_spend = (
                    SELECT max(total_spend)
                    FROM order_totals
                )
                ORDER BY name
                """
        );

        assertEquals(List.of("name", "total_spend"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 30L),
                List.of("Bob", 30L)
        ), result.rows());
    }

    @Test
    void ordersNullsLastAscending() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people ORDER BY name ASC");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L), List.of(2L)), result.rows());
    }

    @Test
    void ordersNullsLastDescending() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people ORDER BY name DESC");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L), List.of(2L)), result.rows());
    }

    @Test
    void executesUngroupedAggregates() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT count(*) AS row_count, count(amount) AS amount_count, sum(amount) AS total,
                       min(amount) AS low, max(amount) AS high, avg(amount) AS average
                FROM sales
                """);

        assertEquals(List.of("row_count", "amount_count", "total", "low", "high", "average"), result.columns());
        assertEquals(List.of(List.of(4L, 3L, 35L, 5L, 20L, 35.0d / 3.0d)), result.rows());
    }

    @Test
    void executesDistinctAggregates() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT count(DISTINCT amount) AS distinct_amounts,
                       sum(DISTINCT amount) AS distinct_total
                FROM sales
                """);

        assertEquals(List.of("distinct_amounts", "distinct_total"), result.columns());
        assertEquals(List.of(List.of(3L, 35L)), result.rows());
    }

    @Test
    void executesGroupedDistinctAggregates() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT region, count(DISTINCT amount) AS distinct_amounts
                FROM sales
                GROUP BY region
                ORDER BY region
                """);

        assertEquals(List.of("region", "distinct_amounts"), result.columns());
        assertEquals(List.of(
                List.of("east", 2L),
                List.of("west", 1L)
        ), result.rows());
    }

    @Test
    void executesScalarExpressionOverAggregate() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, "SELECT count(*) + 1 AS adjusted_count FROM sales");

        assertEquals(List.of("adjusted_count"), result.columns());
        assertEquals(List.of(List.of(5L)), result.rows());
    }

    @Test
    void executesGroupedAggregates() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT region, count(*) AS row_count, sum(amount) AS total, avg(amount) AS average
                FROM sales
                GROUP BY region
                """);

        assertEquals(List.of("region", "row_count", "total", "average"), result.columns());
        assertEquals(List.of(
                List.of("east", 2L, 30L, 15.0d),
                List.of("west", 2L, 5L, 5.0d)
        ), result.rows());
    }

    @Test
    void executesGroupedAggregatesOrderedByAlias() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT region, count(*) AS row_count, sum(amount) AS total
                FROM sales
                GROUP BY region
                ORDER BY total ASC
                """);

        assertEquals(List.of("region", "row_count", "total"), result.columns());
        assertEquals(List.of(
                List.of("west", 2L, 5L),
                List.of("east", 2L, 30L)
        ), result.rows());
    }

    @Test
    void executesHavingOverGroupedAggregate() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT region, sum(amount) AS total
                FROM sales
                GROUP BY region
                HAVING sum(amount) > 10
                ORDER BY region
                """);

        assertEquals(List.of("region", "total"), result.columns());
        assertEquals(List.of(List.of("east", 30L)), result.rows());
    }

    @Test
    void executesHavingWithSelectAlias() {
        Fixture fixture = salesFixture();

        QueryResult result = execute(fixture, """
                SELECT region AS r, sum(amount) AS total
                FROM sales
                GROUP BY region
                HAVING total > 10
                ORDER BY r
                """);

        assertEquals(List.of("r", "total"), result.columns());
        assertEquals(List.of(List.of("east", 30L)), result.rows());
    }

    @Test
    void executesConstantHavingWithoutAggregatingRows() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT 1 AS one FROM people HAVING true");

        assertEquals(List.of("one"), result.columns());
        assertEquals(List.of(List.of(1L), List.of(1L)), result.rows());
    }

    @Test
    void executesCountStarOnEmptyInput() {
        Fixture fixture = emptySalesFixture();

        QueryResult result = execute(fixture, "SELECT count(*) AS row_count, sum(amount) AS total FROM sales");

        assertEquals(List.of("row_count", "total"), result.columns());
        assertEquals(List.of(java.util.Arrays.asList(0L, null)), result.rows());
    }

    @Test
    void executesLowerFunction() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT lower(name) FROM people");

        assertEquals(List.of("lower"), result.columns());
        assertEquals(List.of(
                List.of("alice"),
                List.of("bob")
        ), result.rows());
    }

    @Test
    void executesSubstringFunctionWithDuckDbPositions() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, """
                SELECT
                    substring('abcdef' FROM 1 FOR 2) AS a,
                    substring('abcdef' FROM 0 FOR 2) AS b,
                    substring('abcdef' FROM -2 FOR 2) AS c,
                    substring('abcdef' FROM 4 FOR -2) AS d
                FROM people
                LIMIT 1
                """);

        assertEquals(List.of("a", "b", "c", "d"), result.columns());
        assertEquals(List.of(List.of("ab", "a", "ef", "bc")), result.rows());
    }

    @Test
    void executesExtractFromDate() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, """
                SELECT EXTRACT(YEAR FROM DATE '1994-01-01') AS y,
                       EXTRACT(MONTH FROM DATE '1994-01-01') AS m,
                       EXTRACT(DAY FROM DATE '1994-01-01') AS d
                FROM people
                LIMIT 1
                """);

        assertEquals(List.of("y", "m", "d"), result.columns());
        assertEquals(List.of(List.of(1994L, 1L, 1L)), result.rows());
    }

    @Test
    void extractRejectsUnsupportedDatePart() {
        Fixture fixture = peopleFixture();

        ExecutionException error = assertThrows(
                ExecutionException.class,
                () -> execute(fixture, "SELECT EXTRACT(QUARTER FROM DATE '1994-01-01') FROM people LIMIT 1")
        );

        assertEquals("Unsupported date_part field: quarter", error.getMessage());
    }

    @Test
    void lowerPreservesNulls() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT lower(name) FROM people");

        assertEquals(List.of("lower"), result.columns());
        assertEquals(List.of(
                List.of("alice"),
                java.util.Arrays.asList((Object) null)
        ), result.rows());
    }

    @Test
    void arithmeticPreservesNulls() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "SELECT id + NULL AS value FROM people LIMIT 1");

        assertEquals(List.of("value"), result.columns());
        assertEquals(List.of(java.util.Arrays.asList((Object) null)), result.rows());
    }

    @Test
    void nullComparisonDoesNotPassFilter() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE name = NULL");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void orUsesSqlThreeValuedLogic() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE name = 'Alice' OR name = NULL");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void andUsesSqlThreeValuedLogic() {
        Fixture fixture = peopleWithNullFixture();

        QueryResult result = execute(fixture, "SELECT id FROM people WHERE name = 'Alice' AND NULL");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void executesExplain() {
        Fixture fixture = peopleFixture();

        QueryResult result = execute(fixture, "EXPLAIN SELECT id FROM people WHERE id = 1");

        assertEquals(List.of("explain"), result.columns());
        String plan = (String) result.rows().getFirst().getFirst();
        assertPlanContains(plan, "Logical Plan", "PROJECTION", "FILTER", "SEQ_SCAN", "Table:", "people");
        assertPlanContains(plan, "Physical Plan", "Expression:", "(id#0 EQUAL 1)", "Projections:", "id#0");
    }

    @Test
    void executesSelectFromCsvPath(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("people.csv");
        Files.writeString(csv, """
                id,name
                1,Alice
                2,Bob
                """);

        Fixture fixture = emptyFixture();

        QueryResult result = execute(fixture, "SELECT name FROM '" + sqlString(csv.toString()) + "' WHERE id = 1");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void readsQuotedCsvFields(@TempDir Path tempDir) throws Exception {
        Path csv = tempDir.resolve("quoted.csv");
        Files.writeString(csv, "id,note\n1,\"Alice, A.\"\n2,\"Bob said \"\"hello\"\"\"\n");

        Fixture fixture = emptyFixture();

        QueryResult result = execute(fixture, "SELECT note FROM '" + sqlString(csv.toString()) + "' ORDER BY id");

        assertEquals(List.of("note"), result.columns());
        assertEquals(List.of(
                List.of("Alice, A."),
                List.of("Bob said \"hello\"")
        ), result.rows());
    }

    private QueryResult execute(Fixture fixture, String sql) {
        Statement statement = parser.parse(sql);
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);
        return new QueryExecutor(fixture.storageManager).execute(logical);
    }

    private void assertPlanContains(String plan, String... expectedValues) {
        for (String expectedValue : expectedValues) {
            assertTrue(plan.contains(expectedValue), "Expected plan to contain '" + expectedValue + "':\n" + plan);
        }
    }

    private String sqlString(String value) {
        return value.replace("'", "''");
    }

    private Fixture peopleFixture() {
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
        InMemoryTableStorage storage = storageManager.createTable(table);
        storage.appendRow(List.of(1L, "Alice"));
        storage.appendRow(List.of(2L, "Bob"));

        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture peopleWithNullFixture() {
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
        InMemoryTableStorage storage = storageManager.createTable(table);
        storage.appendRow(List.of(1L, "Alice"));
        storage.appendRow(java.util.Arrays.asList(2L, null));

        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture salesFixture() {
        Fixture fixture = emptySalesFixture();
        TableCatalogEntry table = fixture.catalog().lookupTable(fixture.transaction(), new QualifiedName(List.of("sales")));
        InMemoryTableStorage storage = fixture.storageManager().getTable(table);
        storage.appendRow(List.of("east", 10L));
        storage.appendRow(List.of("east", 20L));
        storage.appendRow(List.of("west", 5L));
        storage.appendRow(java.util.Arrays.asList("west", null));
        return fixture;
    }

    private Fixture emptySalesFixture() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("sales")),
                List.of(
                        new ColumnDefinition("region", TypeName.TEXT),
                        new ColumnDefinition("amount", TypeName.BIGINT)
                )
        );

        StorageManager storageManager = new StorageManager();
        storageManager.createTable(table);

        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture peopleOrdersFixture() {
        Fixture fixture = peopleFixture();
        TableCatalogEntry orders = fixture.catalog().createTable(
                fixture.transaction(),
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );
        InMemoryTableStorage storage = fixture.storageManager().createTable(orders);
        storage.appendRow(List.of(1L, 20L));
        storage.appendRow(List.of(1L, 10L));
        storage.appendRow(List.of(2L, 30L));
        storage.appendRow(List.of(3L, 40L));
        return fixture;
    }

    private Fixture peopleOrdersWithUnmatchedFixture() {
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
        InMemoryTableStorage peopleStorage = storageManager.createTable(people);
        peopleStorage.appendRow(List.of(1L, "Alice"));
        peopleStorage.appendRow(List.of(2L, "Bob"));
        peopleStorage.appendRow(List.of(3L, "Charlie"));

        InMemoryTableStorage orderStorage = storageManager.createTable(orders);
        orderStorage.appendRow(List.of(1L, 20L));
        orderStorage.appendRow(List.of(1L, 10L));
        orderStorage.appendRow(List.of(2L, 30L));
        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture customersOrdersLineitemsFixture() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        TableCatalogEntry customers = catalog.createTable(
                transaction,
                new QualifiedName(List.of("customers")),
                List.of(
                        new ColumnDefinition("cust_id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        TableCatalogEntry orders = catalog.createTable(
                transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("order_id", TypeName.BIGINT),
                        new ColumnDefinition("cust_id", TypeName.BIGINT)
                )
        );
        TableCatalogEntry lineitems = catalog.createTable(
                transaction,
                new QualifiedName(List.of("lineitems")),
                List.of(
                        new ColumnDefinition("order_id", TypeName.BIGINT),
                        new ColumnDefinition("amount", TypeName.BIGINT)
                )
        );

        StorageManager storageManager = new StorageManager();
        InMemoryTableStorage customerStorage = storageManager.createTable(customers);
        customerStorage.appendRow(List.of(1L, "Alice"));
        customerStorage.appendRow(List.of(2L, "Bob"));

        InMemoryTableStorage orderStorage = storageManager.createTable(orders);
        orderStorage.appendRow(List.of(10L, 1L));
        orderStorage.appendRow(List.of(20L, 2L));
        orderStorage.appendRow(List.of(30L, 3L));

        InMemoryTableStorage lineitemStorage = storageManager.createTable(lineitems);
        lineitemStorage.appendRow(List.of(10L, 20L));
        lineitemStorage.appendRow(List.of(10L, 5L));
        lineitemStorage.appendRow(List.of(20L, 30L));
        lineitemStorage.appendRow(List.of(40L, 99L));

        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture emptyFixture() {
        Catalog catalog = new Catalog();
        Transaction transaction = transactionManager.startTransaction();
        StorageManager storageManager = new StorageManager();
        return new Fixture(catalog, transaction, storageManager);
    }

    private record Fixture(Catalog catalog, dev.trentdb.transaction.Transaction transaction, StorageManager storageManager) {
    }
}
