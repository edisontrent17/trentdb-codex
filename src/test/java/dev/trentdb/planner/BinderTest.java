package dev.trentdb.planner;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.SortDirection;
import dev.trentdb.ast.TypeName;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.Statement;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.function.FunctionException;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.logical.LogicalAggregate;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalJoin;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalOrder;
import dev.trentdb.planner.logical.LogicalPlanPrinter;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.planner.logical.LogicalOperatorType;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.types.LogicalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinderTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void bindsSelectStarFromTable() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT * FROM people");

        BoundTableRef from = assertInstanceOf(BoundTableRef.class, bound.from());
        assertSame(fixture.table, from.table());
        assertEquals(2, bound.selectList().size());

        BoundColumnRefExpression id = assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(0));
        assertEquals("id", id.name());
        assertEquals(LogicalType.BIGINT, id.logicalType());
        assertEquals(0, id.ordinal());

        BoundColumnRefExpression name = assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(1));
        assertEquals("name", name.name());
        assertEquals(LogicalType.TEXT, name.logicalType());
        assertEquals(1, name.ordinal());
    }

    @Test
    void bindsExplicitColumns() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, id FROM people");

        assertEquals(2, bound.selectList().size());
        assertEquals("name", assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(0)).name());
        assertEquals("id", assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(1)).name());
    }

    @Test
    void bindsSelectAliases() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name AS person_name, lower(name) lowered FROM people");

        assertEquals(List.of("person_name", "lowered"), bound.selectNames());
    }

    @Test
    void rejectsMissingTable() {
        Fixture fixture = emptyFixture();

        assertThrows(CatalogException.class, () -> bindSelect(fixture, "SELECT * FROM missing"));
    }

    @Test
    void rejectsMissingColumn() {
        Fixture fixture = peopleFixture();

        CatalogException error = assertThrows(CatalogException.class, () -> bindSelect(fixture, "SELECT missing FROM people"));
        assertEquals("Column not found: missing", error.getMessage());
    }

    @Test
    void bindsArithmeticSelectExpression() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id + 1 AS next_id FROM people");

        assertEquals(List.of("next_id"), bound.selectNames());
        BoundBinaryExpression expression = assertInstanceOf(BoundBinaryExpression.class, bound.selectList().getFirst());
        assertEquals(BinaryOperator.ADD, expression.operator());
        assertEquals(LogicalType.BIGINT, expression.logicalType());

        BoundColumnRefExpression left = assertInstanceOf(BoundColumnRefExpression.class, expression.left());
        assertEquals("id", left.name());

        BoundLiteralExpression right = assertInstanceOf(BoundLiteralExpression.class, expression.right());
        assertEquals(1L, right.value());
    }

    @Test
    void bindsScalarFunctionInSelectList() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT lower(name) FROM people");

        BoundFunctionExpression function = assertInstanceOf(BoundFunctionExpression.class, bound.selectList().getFirst());
        assertEquals("lower", function.name());
        assertEquals(LogicalType.TEXT, function.logicalType());

        BoundColumnRefExpression argument = assertInstanceOf(BoundColumnRefExpression.class, function.arguments().getFirst());
        assertEquals("name", argument.name());
    }

    @Test
    void rejectsMissingScalarFunction() {
        Fixture fixture = peopleFixture();

        FunctionException error = assertThrows(FunctionException.class, () -> bindSelect(fixture, "SELECT unknown(name) FROM people"));
        assertEquals("Scalar function not found: unknown", error.getMessage());
    }

    @Test
    void rejectsWrongScalarFunctionArgumentCount() {
        Fixture fixture = peopleFixture();

        FunctionException error = assertThrows(FunctionException.class, () -> bindSelect(fixture, "SELECT lower(name, name) FROM people"));
        assertEquals("Scalar function lower expects 1 arguments but got 2", error.getMessage());
    }

    @Test
    void rejectsWrongScalarFunctionArgumentType() {
        Fixture fixture = peopleFixture();

        FunctionException error = assertThrows(FunctionException.class, () -> bindSelect(fixture, "SELECT lower(id) FROM people"));
        assertEquals("Scalar function lower argument 1 expects TEXT but got BIGINT", error.getMessage());
    }

    @Test
    void bindsAggregateFunctionInSelectList() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT count(*), sum(id) FROM people");

        BoundAggregateExpression count = assertInstanceOf(BoundAggregateExpression.class, bound.selectList().get(0));
        assertEquals("count", count.name());
        assertEquals(LogicalType.BIGINT, count.logicalType());
        assertEquals(true, count.starArgument());

        BoundAggregateExpression sum = assertInstanceOf(BoundAggregateExpression.class, bound.selectList().get(1));
        assertEquals("sum", sum.name());
        assertEquals(LogicalType.BIGINT, sum.logicalType());
        assertInstanceOf(BoundColumnRefExpression.class, sum.arguments().getFirst());
    }

    @Test
    void bindsGroupByExpression() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, count(*) FROM people GROUP BY name");

        assertEquals(1, bound.groupBy().size());
        assertEquals(bound.groupBy().getFirst(), bound.selectList().getFirst());
    }

    @Test
    void rejectsUngroupedColumnWithAggregate() {
        Fixture fixture = peopleFixture();

        BinderException error = assertThrows(BinderException.class, () -> bindSelect(fixture, "SELECT name, count(*) FROM people"));

        assertEquals("Column must appear in GROUP BY or be used in an aggregate function", error.getMessage());
    }

    @Test
    void rejectsAggregateInWhere() {
        Fixture fixture = peopleFixture();

        BinderException error = assertThrows(BinderException.class, () -> bindSelect(fixture, "SELECT id FROM people WHERE count(*) > 0"));

        assertEquals("Aggregate functions are not allowed in this clause: count", error.getMessage());
    }

    @Test
    void bindsAggregateInsideScalarExpression() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT count(*) + 1 FROM people");

        BoundBinaryExpression expression = assertInstanceOf(BoundBinaryExpression.class, bound.selectList().getFirst());
        assertEquals(BinaryOperator.ADD, expression.operator());
        assertInstanceOf(BoundAggregateExpression.class, expression.left());
        assertInstanceOf(BoundLiteralExpression.class, expression.right());
    }

    @Test
    void bindsWhereComparison() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people WHERE id = 1");

        BoundBinaryExpression predicate = assertInstanceOf(BoundBinaryExpression.class, bound.where());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());

        BoundColumnRefExpression left = assertInstanceOf(BoundColumnRefExpression.class, predicate.left());
        assertEquals("id", left.name());

        BoundLiteralExpression right = assertInstanceOf(BoundLiteralExpression.class, predicate.right());
        assertEquals(LogicalType.BIGINT, right.logicalType());
        assertEquals(1L, right.value());
    }

    @Test
    void bindsWhereAndPredicate() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people WHERE id = 1 AND name = 'alice'");

        BoundBinaryExpression predicate = assertInstanceOf(BoundBinaryExpression.class, bound.where());
        assertEquals(BinaryOperator.AND, predicate.operator());
        assertInstanceOf(BoundBinaryExpression.class, predicate.left());
        assertInstanceOf(BoundBinaryExpression.class, predicate.right());
    }

    @Test
    void bindsWhereArithmeticComparison() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people WHERE id + 1 = 2");

        BoundBinaryExpression predicate = assertInstanceOf(BoundBinaryExpression.class, bound.where());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());

        BoundBinaryExpression left = assertInstanceOf(BoundBinaryExpression.class, predicate.left());
        assertEquals(BinaryOperator.ADD, left.operator());
        assertEquals(LogicalType.BIGINT, left.logicalType());
    }

    @Test
    void bindsWhereInPredicate() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people WHERE id IN (1, 2)");

        BoundInExpression predicate = assertInstanceOf(BoundInExpression.class, bound.where());
        assertEquals(false, predicate.negated());
        assertEquals(LogicalType.BOOLEAN, predicate.logicalType());
        assertInstanceOf(BoundColumnRefExpression.class, predicate.input());
        assertEquals(2, predicate.candidates().size());
    }

    @Test
    void bindsWhereNotInPredicate() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people WHERE name NOT IN ('Alice', 'Bob')");

        BoundInExpression predicate = assertInstanceOf(BoundInExpression.class, bound.where());
        assertEquals(true, predicate.negated());
        assertEquals(LogicalType.BOOLEAN, predicate.logicalType());
    }

    @Test
    void rejectsInPredicateWithIncomparableCandidate() {
        Fixture fixture = peopleFixture();

        BinderException error = assertThrows(BinderException.class, () -> bindSelect(fixture, "SELECT id FROM people WHERE id IN ('1')"));

        assertEquals("IN candidate cannot compare BIGINT and TEXT", error.getMessage());
    }

    @Test
    void rejectsTextArithmetic() {
        Fixture fixture = peopleFixture();

        BinderException error = assertThrows(BinderException.class, () -> bindSelect(fixture, "SELECT name + 1 FROM people"));
        assertEquals("Operator ADD requires numeric operands but got TEXT and BIGINT", error.getMessage());
    }

    @Test
    void rejectsMissingWhereColumn() {
        Fixture fixture = peopleFixture();

        CatalogException error = assertThrows(CatalogException.class, () -> bindSelect(fixture, "SELECT id FROM people WHERE missing = 1"));
        assertEquals("Column not found: missing", error.getMessage());
    }

    @Test
    void bindsLimit() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people LIMIT 1");

        assertEquals(1L, bound.limit());
    }

    @Test
    void bindsOrderByColumn() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people ORDER BY id DESC");

        assertEquals(1, bound.orderBy().size());
        assertEquals(SortDirection.DESC, bound.orderBy().getFirst().direction());
        assertEquals("id", assertInstanceOf(BoundColumnRefExpression.class, bound.orderBy().getFirst().expression()).name());
    }

    @Test
    void bindsOrderByExpression() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people ORDER BY id + 1");

        assertEquals(BinaryOperator.ADD, assertInstanceOf(BoundBinaryExpression.class, bound.orderBy().getFirst().expression()).operator());
    }

    @Test
    void bindsOrderByAlias() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name AS n FROM people ORDER BY n");

        assertEquals(bound.selectList().getFirst(), bound.orderBy().getFirst().expression());
    }

    @Test
    void bindsOrderBySelectListPosition() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, id FROM people ORDER BY 2");

        assertEquals(bound.selectList().get(1), bound.orderBy().getFirst().expression());
    }

    @Test
    void bindsInnerJoin() {
        Fixture fixture = peopleFixture();
        fixture.catalog.createTable(
                fixture.transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );

        BoundSelectStatement bound = bindSelect(
                fixture,
                "SELECT p.id, o.person_id FROM people p JOIN orders o ON p.id = o.person_id"
        );

        BoundJoinRef join = assertInstanceOf(BoundJoinRef.class, bound.from());
        assertSame(fixture.table, join.left().table());
        assertEquals("p", join.left().alias());
        assertEquals("orders", join.right().table().name());
        assertEquals("o", join.right().alias());

        BoundBinaryExpression predicate = assertInstanceOf(BoundBinaryExpression.class, join.condition());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());
        BoundColumnRefExpression left = assertInstanceOf(BoundColumnRefExpression.class, predicate.left());
        BoundColumnRefExpression right = assertInstanceOf(BoundColumnRefExpression.class, predicate.right());
        assertEquals(0, left.ordinal());
        assertEquals(2, right.ordinal());
    }

    @Test
    void rejectsAmbiguousJoinColumnReference() {
        Fixture fixture = peopleFixture();
        fixture.catalog.createTable(
                fixture.transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("person_id", TypeName.BIGINT)
                )
        );

        BinderException error = assertThrows(
                BinderException.class,
                () -> bindSelect(fixture, "SELECT id FROM people p JOIN orders o ON p.id = o.person_id")
        );

        assertEquals("Column reference is ambiguous: id", error.getMessage());
    }

    @Test
    void bindsAggregateOrderByAliasToOutputColumn() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, count(*) AS total FROM people GROUP BY name ORDER BY total DESC");

        BoundOutputColumnExpression order = assertInstanceOf(BoundOutputColumnExpression.class, bound.orderBy().getFirst().expression());
        assertEquals("total", order.name());
        assertEquals(1, order.ordinal());
        assertEquals(LogicalType.BIGINT, order.logicalType());
        assertEquals(SortDirection.DESC, bound.orderBy().getFirst().direction());
    }

    @Test
    void bindsAggregateOrderByGroupColumnToOutputColumn() {
        Fixture fixture = peopleFixture();

        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, count(*) FROM people GROUP BY name ORDER BY name");

        BoundOutputColumnExpression order = assertInstanceOf(BoundOutputColumnExpression.class, bound.orderBy().getFirst().expression());
        assertEquals("name", order.name());
        assertEquals(0, order.ordinal());
        assertEquals(LogicalType.TEXT, order.logicalType());
    }

    @Test
    void rejectsOrderByPositionOutsideSelectList() {
        Fixture fixture = peopleFixture();

        BinderException error = assertThrows(BinderException.class, () -> bindSelect(fixture, "SELECT name FROM people ORDER BY 2"));

        assertEquals("ORDER BY position 2 is not in select list", error.getMessage());
    }

    @Test
    void plansLogicalProjectionOverGet() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, logical);
        assertEquals(LogicalOperatorType.LOGICAL_PROJECTION, projection.type());
        assertEquals(1, projection.expressions().size());

        LogicalGet get = assertInstanceOf(LogicalGet.class, projection.child());
        assertEquals(LogicalOperatorType.LOGICAL_GET, get.type());
        assertSame(fixture.table, get.tableRef().table());
    }

    @Test
    void plansLogicalProjectionNames() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT name AS person_name FROM people");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, logical);
        assertEquals(List.of("person_name"), projection.names());
    }

    @Test
    void plansLogicalProjectionOverFilterOverGet() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people WHERE id = 1");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, logical);
        assertEquals(LogicalOperatorType.LOGICAL_PROJECTION, projection.type());
        LogicalFilter filter = assertInstanceOf(LogicalFilter.class, projection.child());
        assertEquals(LogicalOperatorType.LOGICAL_FILTER, filter.type());
        assertSame(bound.where(), filter.predicate());

        LogicalGet get = assertInstanceOf(LogicalGet.class, filter.child());
        assertSame(fixture.table, get.tableRef().table());
    }

    @Test
    void plansLogicalProjectionOverJoin() {
        Fixture fixture = peopleFixture();
        fixture.catalog.createTable(
                fixture.transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );
        BoundSelectStatement bound = bindSelect(
                fixture,
                "SELECT p.id FROM people p JOIN orders o ON p.id = o.person_id"
        );

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, logical);
        LogicalJoin join = assertInstanceOf(LogicalJoin.class, projection.child());
        assertEquals(LogicalOperatorType.LOGICAL_COMPARISON_JOIN, join.type());
        assertEquals("people", join.left().table().name());
        assertEquals("orders", join.right().table().name());
    }

    @Test
    void plansLogicalLimitOverProjection() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people LIMIT 1");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalLimit limit = assertInstanceOf(LogicalLimit.class, logical);
        assertEquals(LogicalOperatorType.LOGICAL_LIMIT, limit.type());
        assertEquals(1L, limit.limit());
        assertInstanceOf(LogicalProjection.class, limit.child());
    }

    @Test
    void plansLogicalProjectionOverOrder() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT id FROM people ORDER BY name");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, logical);
        LogicalOrder order = assertInstanceOf(LogicalOrder.class, projection.child());
        assertEquals(LogicalOperatorType.LOGICAL_ORDER_BY, order.type());
        assertEquals(1, order.orders().size());
        assertInstanceOf(LogicalGet.class, order.child());
    }

    @Test
    void plansLogicalAggregateOverGet() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, count(*) FROM people GROUP BY name");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, logical);
        LogicalAggregate aggregate = assertInstanceOf(LogicalAggregate.class, projection.child());
        assertEquals(LogicalOperatorType.LOGICAL_AGGREGATE_AND_GROUP_BY, aggregate.type());
        assertEquals(1, aggregate.groups().size());
        assertEquals(2, aggregate.selectList().size());
        assertInstanceOf(LogicalGet.class, aggregate.child());
    }

    @Test
    void plansLogicalOrderOverAggregate() {
        Fixture fixture = peopleFixture();
        BoundSelectStatement bound = bindSelect(fixture, "SELECT name, count(*) AS total FROM people GROUP BY name ORDER BY total");

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalOrder order = assertInstanceOf(LogicalOrder.class, logical);
        assertEquals(LogicalOperatorType.LOGICAL_ORDER_BY, order.type());
        LogicalProjection projection = assertInstanceOf(LogicalProjection.class, order.child());
        LogicalAggregate aggregate = assertInstanceOf(LogicalAggregate.class, projection.child());
        assertEquals(LogicalOperatorType.LOGICAL_AGGREGATE_AND_GROUP_BY, aggregate.type());
    }

    @Test
    void bindsExplainSelect() {
        Fixture fixture = peopleFixture();
        Statement statement = parser.parse("EXPLAIN SELECT id FROM people");

        BoundExplainStatement explain = assertInstanceOf(BoundExplainStatement.class, new Binder(fixture.catalog).bind(fixture.transaction, statement));

        assertInstanceOf(BoundSelectStatement.class, explain.statement());
    }

    @Test
    void plansExplainSelect() {
        Fixture fixture = peopleFixture();
        Statement statement = parser.parse("EXPLAIN SELECT id FROM people WHERE id = 1");
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);

        LogicalOperator logical = new LogicalPlanner().plan(bound);

        LogicalExplain explain = assertInstanceOf(LogicalExplain.class, logical);
        assertInstanceOf(LogicalProjection.class, explain.child());
    }

    @Test
    void printsExplainLogicalPlan() {
        Fixture fixture = peopleFixture();
        Statement statement = parser.parse("EXPLAIN SELECT id FROM people WHERE id = 1");
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalProjection [1]
                    LogicalFilter
                      LogicalGet people
                """, new LogicalPlanPrinter().print(logical));
    }

    @Test
    void printsLimitLogicalPlan() {
        Fixture fixture = peopleFixture();
        Statement statement = parser.parse("EXPLAIN SELECT id FROM people LIMIT 1");
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalLimit 1
                    LogicalProjection [1]
                      LogicalGet people
                """, new LogicalPlanPrinter().print(logical));
    }

    @Test
    void printsOrderLogicalPlan() {
        Fixture fixture = peopleFixture();
        Statement statement = parser.parse("EXPLAIN SELECT id FROM people ORDER BY name");
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalProjection [1]
                    LogicalOrder [1]
                      LogicalGet people
                """, new LogicalPlanPrinter().print(logical));
    }

    @Test
    void printsAggregateLogicalPlan() {
        Fixture fixture = peopleFixture();
        Statement statement = parser.parse("EXPLAIN SELECT name, count(*) FROM people GROUP BY name");
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalProjection [2]
                    LogicalAggregate groups=[1] expressions=[2]
                      LogicalGet people
                """, new LogicalPlanPrinter().print(logical));
    }

    @Test
    void printsJoinLogicalPlan() {
        Fixture fixture = peopleFixture();
        fixture.catalog.createTable(
                fixture.transaction,
                new QualifiedName(List.of("orders")),
                List.of(
                        new ColumnDefinition("person_id", TypeName.BIGINT),
                        new ColumnDefinition("total", TypeName.BIGINT)
                )
        );
        Statement statement = parser.parse("EXPLAIN SELECT p.id FROM people p JOIN orders o ON p.id = o.person_id");
        BoundStatement bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        LogicalOperator logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalProjection [1]
                    LogicalComparisonJoin
                      LogicalGet people
                      LogicalGet orders
                """, new LogicalPlanPrinter().print(logical));
    }

    private BoundSelectStatement bindSelect(Fixture fixture, String sql) {
        Statement statement = parser.parse(sql);
        return assertInstanceOf(BoundSelectStatement.class, new Binder(fixture.catalog).bind(fixture.transaction, statement));
    }

    private Fixture peopleFixture() {
        Fixture fixture = emptyFixture();
        TableCatalogEntry table = fixture.catalog.createTable(
                fixture.transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        return new Fixture(fixture.catalog, fixture.transaction, table);
    }

    private Fixture emptyFixture() {
        Catalog catalog = new Catalog();
        dev.trentdb.transaction.Transaction transaction = transactionManager.startTransaction();
        return new Fixture(catalog, transaction, null);
    }

    private record Fixture(Catalog catalog, dev.trentdb.transaction.Transaction transaction, dev.trentdb.catalog.TableCatalogEntry table) {
    }
}
