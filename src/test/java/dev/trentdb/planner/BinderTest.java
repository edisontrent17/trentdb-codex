package dev.trentdb.planner;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.CatalogException;
import dev.trentdb.function.FunctionException;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalPlanPrinter;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.planner.logical.LogicalProjection;
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
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT * FROM people");

        assertSame(fixture.table, bound.from().table());
        assertEquals(2, bound.selectList().size());

        var id = assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(0));
        assertEquals("id", id.name());
        assertEquals(LogicalType.BIGINT, id.logicalType());
        assertEquals(0, id.ordinal());

        var name = assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(1));
        assertEquals("name", name.name());
        assertEquals(LogicalType.TEXT, name.logicalType());
        assertEquals(1, name.ordinal());
    }

    @Test
    void bindsExplicitColumns() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT name, id FROM people");

        assertEquals(2, bound.selectList().size());
        assertEquals("name", assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(0)).name());
        assertEquals("id", assertInstanceOf(BoundColumnRefExpression.class, bound.selectList().get(1)).name());
    }

    @Test
    void bindsSelectAliases() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT name AS person_name, lower(name) lowered FROM people");

        assertEquals(List.of("person_name", "lowered"), bound.selectNames());
    }

    @Test
    void rejectsMissingTable() {
        var fixture = emptyFixture();

        assertThrows(CatalogException.class, () -> bindSelect(fixture, "SELECT * FROM missing"));
    }

    @Test
    void rejectsMissingColumn() {
        var fixture = peopleFixture();

        var error = assertThrows(CatalogException.class, () -> bindSelect(fixture, "SELECT missing FROM people"));
        assertEquals("Column not found: missing", error.getMessage());
    }

    @Test
    void bindsArithmeticSelectExpression() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT id + 1 AS next_id FROM people");

        assertEquals(List.of("next_id"), bound.selectNames());
        var expression = assertInstanceOf(BoundBinaryExpression.class, bound.selectList().getFirst());
        assertEquals(BinaryOperator.ADD, expression.operator());
        assertEquals(LogicalType.BIGINT, expression.logicalType());

        var left = assertInstanceOf(BoundColumnRefExpression.class, expression.left());
        assertEquals("id", left.name());

        var right = assertInstanceOf(BoundLiteralExpression.class, expression.right());
        assertEquals(1L, right.value());
    }

    @Test
    void bindsScalarFunctionInSelectList() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT lower(name) FROM people");

        var function = assertInstanceOf(BoundFunctionExpression.class, bound.selectList().getFirst());
        assertEquals("lower", function.name());
        assertEquals(LogicalType.TEXT, function.logicalType());

        var argument = assertInstanceOf(BoundColumnRefExpression.class, function.arguments().getFirst());
        assertEquals("name", argument.name());
    }

    @Test
    void rejectsMissingScalarFunction() {
        var fixture = peopleFixture();

        var error = assertThrows(FunctionException.class, () -> bindSelect(fixture, "SELECT unknown(name) FROM people"));
        assertEquals("Scalar function not found: unknown", error.getMessage());
    }

    @Test
    void rejectsWrongScalarFunctionArgumentCount() {
        var fixture = peopleFixture();

        var error = assertThrows(FunctionException.class, () -> bindSelect(fixture, "SELECT lower(name, name) FROM people"));
        assertEquals("Scalar function lower expects 1 arguments but got 2", error.getMessage());
    }

    @Test
    void rejectsWrongScalarFunctionArgumentType() {
        var fixture = peopleFixture();

        var error = assertThrows(FunctionException.class, () -> bindSelect(fixture, "SELECT lower(id) FROM people"));
        assertEquals("Scalar function lower argument 1 expects TEXT but got BIGINT", error.getMessage());
    }

    @Test
    void bindsWhereComparison() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT id FROM people WHERE id = 1");

        var predicate = assertInstanceOf(BoundBinaryExpression.class, bound.where());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());

        var left = assertInstanceOf(BoundColumnRefExpression.class, predicate.left());
        assertEquals("id", left.name());

        var right = assertInstanceOf(BoundLiteralExpression.class, predicate.right());
        assertEquals(LogicalType.BIGINT, right.logicalType());
        assertEquals(1L, right.value());
    }

    @Test
    void bindsWhereAndPredicate() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT id FROM people WHERE id = 1 AND name = 'alice'");

        var predicate = assertInstanceOf(BoundBinaryExpression.class, bound.where());
        assertEquals(BinaryOperator.AND, predicate.operator());
        assertInstanceOf(BoundBinaryExpression.class, predicate.left());
        assertInstanceOf(BoundBinaryExpression.class, predicate.right());
    }

    @Test
    void bindsWhereArithmeticComparison() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT id FROM people WHERE id + 1 = 2");

        var predicate = assertInstanceOf(BoundBinaryExpression.class, bound.where());
        assertEquals(BinaryOperator.EQUAL, predicate.operator());

        var left = assertInstanceOf(BoundBinaryExpression.class, predicate.left());
        assertEquals(BinaryOperator.ADD, left.operator());
        assertEquals(LogicalType.BIGINT, left.logicalType());
    }

    @Test
    void rejectsTextArithmetic() {
        var fixture = peopleFixture();

        var error = assertThrows(BinderException.class, () -> bindSelect(fixture, "SELECT name + 1 FROM people"));
        assertEquals("Operator ADD requires numeric operands but got TEXT and BIGINT", error.getMessage());
    }

    @Test
    void rejectsMissingWhereColumn() {
        var fixture = peopleFixture();

        var error = assertThrows(CatalogException.class, () -> bindSelect(fixture, "SELECT id FROM people WHERE missing = 1"));
        assertEquals("Column not found: missing", error.getMessage());
    }

    @Test
    void bindsLimit() {
        var fixture = peopleFixture();

        var bound = bindSelect(fixture, "SELECT id FROM people LIMIT 1");

        assertEquals(1L, bound.limit());
    }

    @Test
    void plansLogicalProjectionOverGet() {
        var fixture = peopleFixture();
        var bound = bindSelect(fixture, "SELECT id FROM people");

        var logical = new LogicalPlanner().plan(bound);

        var projection = assertInstanceOf(LogicalProjection.class, logical);
        assertEquals(1, projection.expressions().size());

        var get = assertInstanceOf(LogicalGet.class, projection.child());
        assertSame(fixture.table, get.tableRef().table());
    }

    @Test
    void plansLogicalProjectionNames() {
        var fixture = peopleFixture();
        var bound = bindSelect(fixture, "SELECT name AS person_name FROM people");

        var logical = new LogicalPlanner().plan(bound);

        var projection = assertInstanceOf(LogicalProjection.class, logical);
        assertEquals(List.of("person_name"), projection.names());
    }

    @Test
    void plansLogicalProjectionOverFilterOverGet() {
        var fixture = peopleFixture();
        var bound = bindSelect(fixture, "SELECT id FROM people WHERE id = 1");

        var logical = new LogicalPlanner().plan(bound);

        var projection = assertInstanceOf(LogicalProjection.class, logical);
        var filter = assertInstanceOf(LogicalFilter.class, projection.child());
        assertSame(bound.where(), filter.predicate());

        var get = assertInstanceOf(LogicalGet.class, filter.child());
        assertSame(fixture.table, get.tableRef().table());
    }

    @Test
    void plansLogicalLimitOverProjection() {
        var fixture = peopleFixture();
        var bound = bindSelect(fixture, "SELECT id FROM people LIMIT 1");

        var logical = new LogicalPlanner().plan(bound);

        var limit = assertInstanceOf(LogicalLimit.class, logical);
        assertEquals(1L, limit.limit());
        assertInstanceOf(LogicalProjection.class, limit.child());
    }

    @Test
    void bindsExplainSelect() {
        var fixture = peopleFixture();
        var statement = parser.parse("EXPLAIN SELECT id FROM people");

        var explain = assertInstanceOf(BoundExplainStatement.class, new Binder(fixture.catalog).bind(fixture.transaction, statement));

        assertInstanceOf(BoundSelectStatement.class, explain.statement());
    }

    @Test
    void plansExplainSelect() {
        var fixture = peopleFixture();
        var statement = parser.parse("EXPLAIN SELECT id FROM people WHERE id = 1");
        var bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);

        var logical = new LogicalPlanner().plan(bound);

        var explain = assertInstanceOf(LogicalExplain.class, logical);
        assertInstanceOf(LogicalProjection.class, explain.child());
    }

    @Test
    void printsExplainLogicalPlan() {
        var fixture = peopleFixture();
        var statement = parser.parse("EXPLAIN SELECT id FROM people WHERE id = 1");
        var bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        var logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalProjection [1]
                    LogicalFilter
                      LogicalGet people
                """, new LogicalPlanPrinter().print(logical));
    }

    @Test
    void printsLimitLogicalPlan() {
        var fixture = peopleFixture();
        var statement = parser.parse("EXPLAIN SELECT id FROM people LIMIT 1");
        var bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        var logical = new LogicalPlanner().plan(bound);

        assertEquals("""
                LogicalExplain
                  LogicalLimit 1
                    LogicalProjection [1]
                      LogicalGet people
                """, new LogicalPlanPrinter().print(logical));
    }

    private BoundSelectStatement bindSelect(Fixture fixture, String sql) {
        var statement = parser.parse(sql);
        return assertInstanceOf(BoundSelectStatement.class, new Binder(fixture.catalog).bind(fixture.transaction, statement));
    }

    private Fixture peopleFixture() {
        var fixture = emptyFixture();
        var table = fixture.catalog.createTable(
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
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        return new Fixture(catalog, transaction, null);
    }

    private record Fixture(Catalog catalog, dev.trentdb.transaction.Transaction transaction, dev.trentdb.catalog.TableCatalogEntry table) {
    }
}
