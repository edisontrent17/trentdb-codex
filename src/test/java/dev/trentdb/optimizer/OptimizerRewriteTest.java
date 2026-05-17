package dev.trentdb.optimizer;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.JoinType;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.SortDirection;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalJoin;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalOrder;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.types.LogicalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimizerRewriteTest {
    @Test
    void expressionRewriterPreservesIdentityWhenUnchanged() {
        BoundExpression expression = binaryLiteralExpression(1L, 3L);

        BoundExpression rewritten = new BoundExpressionRewriter().rewrite(expression);

        assertSame(expression, rewritten);
    }

    @Test
    void expressionRewriterRejectsNullExpression() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BoundExpressionRewriter().rewrite(null)
        );

        assertEquals("Expression must not be null", error.getMessage());
    }

    @Test
    void expressionRewriterRebuildsChangedParents() {
        BoundExpression expression = binaryLiteralExpression(1L, 3L);

        BoundExpression rewritten = new IncrementOneLiterals().rewrite(expression);

        BoundBinaryExpression binary = assertInstanceOf(BoundBinaryExpression.class, rewritten);
        BoundLiteralExpression left = assertInstanceOf(BoundLiteralExpression.class, binary.left());
        BoundLiteralExpression right = assertInstanceOf(BoundLiteralExpression.class, binary.right());
        assertNotSame(expression, rewritten);
        assertEquals(2L, left.value());
        assertEquals(3L, right.value());
    }

    @Test
    void logicalOperatorRewriterRebuildsChangedProjection() {
        LogicalGet get = new LogicalGet(new BoundTableRef(table(), null));
        LogicalProjection projection = new LogicalProjection(
                List.of(binaryLiteralExpression(1L, 3L)),
                List.of("value"),
                get
        );

        LogicalOperator rewritten = new LogicalOperatorRewriter(new IncrementOneLiterals()).rewrite(projection);

        LogicalProjection rewrittenProjection = assertInstanceOf(LogicalProjection.class, rewritten);
        BoundBinaryExpression expression = assertInstanceOf(
                BoundBinaryExpression.class,
                rewrittenProjection.expressions().getFirst()
        );
        BoundLiteralExpression left = assertInstanceOf(BoundLiteralExpression.class, expression.left());
        assertNotSame(projection, rewrittenProjection);
        assertSame(get, rewrittenProjection.child());
        assertEquals(2L, left.value());
    }

    @Test
    void optimizerCollectsRewriteMetricsWhenEnabled() {
        TableCatalogEntry table = table();
        LogicalGet get = new LogicalGet(new BoundTableRef(table, null));
        LogicalProjection projection = new LogicalProjection(
                List.of(new BoundColumnRefExpression(table.columns().getFirst())),
                List.of("value"),
                get
        );
        Optimizer optimizer = new Optimizer(true);

        LogicalOperator optimized = optimizer.optimize(projection);

        Optimizer.Metrics metrics = optimizer.metrics();
        assertSame(projection, optimized);
        assertEquals(2, metrics.logicalOperatorsVisited());
        assertEquals(0, metrics.logicalOperatorsRebuilt());
        assertEquals(1, metrics.boundExpressionsVisited());
        assertEquals(0, metrics.boundExpressionsRebuilt());
        assertEquals(0, metrics.expressionListsRebuilt());
    }

    @Test
    void optimizerFoldsLiteralExpressionsWithoutMetricsCollection() {
        LogicalGet get = new LogicalGet(new BoundTableRef(table(), null));
        LogicalProjection projection = new LogicalProjection(
                List.of(binaryLiteralExpression(1L, 3L)),
                List.of("value"),
                get
        );
        Optimizer optimizer = new Optimizer();

        LogicalOperator optimized = optimizer.optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        BoundLiteralExpression folded = assertInstanceOf(
                BoundLiteralExpression.class,
                optimizedProjection.expressions().getFirst()
        );
        assertEquals(4L, folded.value());
        assertEquals(LogicalType.BIGINT, folded.logicalType());
        assertEquals(0, optimizer.metrics().logicalOperatorsVisited());
    }

    @Test
    void optimizerReportsConstantFoldingMetricsWhenEnabled() {
        LogicalGet get = new LogicalGet(new BoundTableRef(table(), null));
        LogicalProjection projection = new LogicalProjection(
                List.of(binaryLiteralExpression(1L, 3L)),
                List.of("value"),
                get
        );
        Optimizer optimizer = new Optimizer(true);

        LogicalOperator optimized = optimizer.optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        assertInstanceOf(BoundLiteralExpression.class, optimizedProjection.expressions().getFirst());
        Optimizer.Metrics metrics = optimizer.metrics();
        assertEquals(2, metrics.logicalOperatorsVisited());
        assertEquals(1, metrics.logicalOperatorsRebuilt());
        assertEquals(3, metrics.boundExpressionsVisited());
        assertEquals(1, metrics.boundExpressionsRebuilt());
        assertEquals(1, metrics.expressionListsRebuilt());
    }

    @Test
    void optimizerLeavesFailingConstantExpressionsForExecution() {
        BoundExpression expression = new BoundBinaryExpression(
                new BoundLiteralExpression(LogicalType.BIGINT, 1L),
                BinaryOperator.DIVIDE,
                new BoundLiteralExpression(LogicalType.BIGINT, 0L),
                LogicalType.DOUBLE
        );
        LogicalGet get = new LogicalGet(new BoundTableRef(table(), null));
        LogicalProjection projection = new LogicalProjection(List.of(expression), List.of("value"), get);

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        assertInstanceOf(BoundBinaryExpression.class, optimizedProjection.expressions().getFirst());
    }

    @Test
    void optimizerSimplifiesArithmeticIdentityExpressions() {
        TableCatalogEntry table = table();
        BoundColumnRefExpression column = new BoundColumnRefExpression(table.columns().getFirst());
        LogicalProjection projection = new LogicalProjection(
                List.of(new BoundBinaryExpression(
                        column,
                        BinaryOperator.ADD,
                        new BoundLiteralExpression(LogicalType.BIGINT, 0L),
                        LogicalType.BIGINT
                )),
                List.of("value"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        assertSame(column, optimizedProjection.expressions().getFirst());
    }

    @Test
    void optimizerSimplifiesArithmeticWithNullLiteralToTypedNull() {
        TableCatalogEntry table = table();
        BoundColumnRefExpression column = new BoundColumnRefExpression(table.columns().getFirst());
        LogicalProjection projection = new LogicalProjection(
                List.of(new BoundBinaryExpression(
                        column,
                        BinaryOperator.ADD,
                        new BoundLiteralExpression(LogicalType.NULL, null),
                        LogicalType.BIGINT
                )),
                List.of("value"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        BoundLiteralExpression literal = assertInstanceOf(
                BoundLiteralExpression.class,
                optimizedProjection.expressions().getFirst()
        );
        assertEquals(LogicalType.BIGINT, literal.logicalType());
        assertNull(literal.value());
    }

    @Test
    void optimizerKeepsArithmeticSimplificationsThatWouldChangeTypeOrNullSemantics() {
        TableCatalogEntry table = table();
        BoundColumnRefExpression column = new BoundColumnRefExpression(table.columns().getFirst());
        LogicalProjection projection = new LogicalProjection(
                List.of(
                        new BoundBinaryExpression(
                                column,
                                BinaryOperator.DIVIDE,
                                new BoundLiteralExpression(LogicalType.BIGINT, 1L),
                                LogicalType.DOUBLE
                        ),
                        new BoundBinaryExpression(
                                column,
                                BinaryOperator.MULTIPLY,
                                new BoundLiteralExpression(LogicalType.BIGINT, 0L),
                                LogicalType.BIGINT
                        )
                ),
                List.of("divided", "multiplied"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        assertInstanceOf(BoundBinaryExpression.class, optimizedProjection.expressions().get(0));
        assertInstanceOf(BoundBinaryExpression.class, optimizedProjection.expressions().get(1));
    }

    @Test
    void optimizerSimplifiesConjunctionIdentityExpressions() {
        TableCatalogEntry table = table(TypeName.BOOLEAN);
        BoundColumnRefExpression column = new BoundColumnRefExpression(table.columns().getFirst());
        LogicalProjection projection = new LogicalProjection(
                List.of(
                        new BoundBinaryExpression(
                                new BoundLiteralExpression(LogicalType.BOOLEAN, true),
                                BinaryOperator.AND,
                                column,
                                LogicalType.BOOLEAN
                        ),
                        new BoundBinaryExpression(
                                new BoundLiteralExpression(LogicalType.BOOLEAN, false),
                                BinaryOperator.OR,
                                column,
                                LogicalType.BOOLEAN
                        )
                ),
                List.of("anded", "ored"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        assertSame(column, optimizedProjection.expressions().get(0));
        assertSame(column, optimizedProjection.expressions().get(1));
    }

    @Test
    void optimizerSimplifiesConjunctionDominatingConstants() {
        TableCatalogEntry table = table(TypeName.BOOLEAN);
        BoundColumnRefExpression column = new BoundColumnRefExpression(table.columns().getFirst());
        LogicalProjection projection = new LogicalProjection(
                List.of(
                        new BoundBinaryExpression(
                                column,
                                BinaryOperator.AND,
                                new BoundLiteralExpression(LogicalType.BOOLEAN, false),
                                LogicalType.BOOLEAN
                        ),
                        new BoundBinaryExpression(
                                column,
                                BinaryOperator.OR,
                                new BoundLiteralExpression(LogicalType.BOOLEAN, true),
                                LogicalType.BOOLEAN
                        )
                ),
                List.of("anded", "ored"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        BoundLiteralExpression andLiteral = assertInstanceOf(
                BoundLiteralExpression.class,
                optimizedProjection.expressions().get(0)
        );
        BoundLiteralExpression orLiteral = assertInstanceOf(
                BoundLiteralExpression.class,
                optimizedProjection.expressions().get(1)
        );
        assertEquals(false, andLiteral.value());
        assertEquals(true, orLiteral.value());
    }

    @Test
    void optimizerKeepsConjunctionsWithNullLiteral() {
        TableCatalogEntry table = table(TypeName.BOOLEAN);
        BoundColumnRefExpression column = new BoundColumnRefExpression(table.columns().getFirst());
        LogicalProjection projection = new LogicalProjection(
                List.of(
                        new BoundBinaryExpression(
                                column,
                                BinaryOperator.AND,
                                new BoundLiteralExpression(LogicalType.NULL, null),
                                LogicalType.BOOLEAN
                        ),
                        new BoundBinaryExpression(
                                column,
                                BinaryOperator.OR,
                                new BoundLiteralExpression(LogicalType.NULL, null),
                                LogicalType.BOOLEAN
                        )
                ),
                List.of("anded", "ored"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        assertInstanceOf(BoundBinaryExpression.class, optimizedProjection.expressions().get(0));
        assertInstanceOf(BoundBinaryExpression.class, optimizedProjection.expressions().get(1));
    }

    @Test
    void optimizerPrunesUnusedScanColumnsAndRemapsProjectionOrdinals() {
        TableCatalogEntry table = table(
                "people",
                new ColumnDefinition("id", TypeName.BIGINT),
                new ColumnDefinition("name", TypeName.TEXT),
                new ColumnDefinition("age", TypeName.BIGINT)
        );
        LogicalProjection projection = new LogicalProjection(
                List.of(new BoundColumnRefExpression(table.columns().get(1))),
                List.of("name"),
                new LogicalGet(new BoundTableRef(table, null))
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        BoundColumnRefExpression expression = assertInstanceOf(
                BoundColumnRefExpression.class,
                optimizedProjection.expressions().getFirst()
        );
        LogicalGet get = assertInstanceOf(LogicalGet.class, optimizedProjection.child());
        assertEquals(List.of(1), get.projectedOrdinals());
        assertEquals(0, expression.ordinal());
    }

    @Test
    void optimizerKeepsFilterAndOrderColumnsWhilePruningUnusedScanColumns() {
        TableCatalogEntry table = table(
                "people",
                new ColumnDefinition("id", TypeName.BIGINT),
                new ColumnDefinition("name", TypeName.TEXT),
                new ColumnDefinition("age", TypeName.BIGINT),
                new ColumnDefinition("city", TypeName.TEXT)
        );
        BoundColumnRefExpression id = new BoundColumnRefExpression(table.columns().get(0));
        BoundColumnRefExpression name = new BoundColumnRefExpression(table.columns().get(1));
        BoundColumnRefExpression age = new BoundColumnRefExpression(table.columns().get(2));
        BoundExpression predicate = new BoundBinaryExpression(
                age,
                BinaryOperator.GREATER_THAN,
                new BoundLiteralExpression(LogicalType.BIGINT, 18L),
                LogicalType.BOOLEAN
        );
        LogicalProjection projection = new LogicalProjection(
                List.of(name),
                List.of("name"),
                new LogicalOrder(
                        List.of(new BoundOrderByItem(id, SortDirection.ASC)),
                        new LogicalFilter(predicate, new LogicalGet(new BoundTableRef(table, null)))
                )
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        LogicalOrder order = assertInstanceOf(LogicalOrder.class, optimizedProjection.child());
        LogicalFilter filter = assertInstanceOf(LogicalFilter.class, order.child());
        LogicalGet get = assertInstanceOf(LogicalGet.class, filter.child());
        BoundColumnRefExpression projectedName = assertInstanceOf(
                BoundColumnRefExpression.class,
                optimizedProjection.expressions().getFirst()
        );
        BoundColumnRefExpression orderId = assertInstanceOf(
                BoundColumnRefExpression.class,
                order.orders().getFirst().expression()
        );
        BoundBinaryExpression filterPredicate = assertInstanceOf(BoundBinaryExpression.class, filter.predicate());
        BoundColumnRefExpression filterAge = assertInstanceOf(BoundColumnRefExpression.class, filterPredicate.left());
        assertEquals(List.of(0, 1, 2), get.projectedOrdinals());
        assertEquals(1, projectedName.ordinal());
        assertEquals(0, orderId.ordinal());
        assertEquals(2, filterAge.ordinal());
    }

    @Test
    void optimizerPrunesJoinInputsAndRemapsJoinOutputOrdinals() {
        TableCatalogEntry people = table(
                "people",
                new ColumnDefinition("id", TypeName.BIGINT),
                new ColumnDefinition("name", TypeName.TEXT),
                new ColumnDefinition("age", TypeName.BIGINT)
        );
        TableCatalogEntry orders = table(
                "orders",
                new ColumnDefinition("person_id", TypeName.BIGINT),
                new ColumnDefinition("total", TypeName.BIGINT),
                new ColumnDefinition("status", TypeName.TEXT)
        );
        BoundColumnRefExpression peopleId = new BoundColumnRefExpression(people.columns().get(0), 0);
        BoundColumnRefExpression peopleName = new BoundColumnRefExpression(people.columns().get(1), 1);
        BoundColumnRefExpression orderPersonId = new BoundColumnRefExpression(orders.columns().get(0), 3);
        BoundColumnRefExpression orderTotal = new BoundColumnRefExpression(orders.columns().get(1), 4);
        LogicalJoin join = new LogicalJoin(
                new LogicalGet(new BoundTableRef(people, null)),
                new LogicalGet(new BoundTableRef(orders, null)),
                new BoundBinaryExpression(peopleId, BinaryOperator.EQUAL, orderPersonId, LogicalType.BOOLEAN),
                JoinType.INNER
        );
        LogicalProjection projection = new LogicalProjection(
                List.of(peopleName, orderTotal),
                List.of("name", "total"),
                join
        );

        LogicalOperator optimized = new Optimizer().optimize(projection);

        LogicalProjection optimizedProjection = assertInstanceOf(LogicalProjection.class, optimized);
        LogicalJoin optimizedJoin = assertInstanceOf(LogicalJoin.class, optimizedProjection.child());
        LogicalGet left = assertInstanceOf(LogicalGet.class, optimizedJoin.left());
        LogicalGet right = assertInstanceOf(LogicalGet.class, optimizedJoin.right());
        BoundColumnRefExpression projectedName = assertInstanceOf(
                BoundColumnRefExpression.class,
                optimizedProjection.expressions().get(0)
        );
        BoundColumnRefExpression projectedTotal = assertInstanceOf(
                BoundColumnRefExpression.class,
                optimizedProjection.expressions().get(1)
        );
        BoundBinaryExpression condition = assertInstanceOf(BoundBinaryExpression.class, optimizedJoin.condition());
        BoundColumnRefExpression leftKey = assertInstanceOf(BoundColumnRefExpression.class, condition.left());
        BoundColumnRefExpression rightKey = assertInstanceOf(BoundColumnRefExpression.class, condition.right());
        assertEquals(List.of(0, 1), left.projectedOrdinals());
        assertEquals(List.of(0, 1), right.projectedOrdinals());
        assertEquals(1, projectedName.ordinal());
        assertEquals(3, projectedTotal.ordinal());
        assertEquals(0, leftKey.ordinal());
        assertEquals(2, rightKey.ordinal());
    }

    private BoundExpression binaryLiteralExpression(long left, long right) {
        return new BoundBinaryExpression(
                new BoundLiteralExpression(LogicalType.BIGINT, left),
                BinaryOperator.ADD,
                new BoundLiteralExpression(LogicalType.BIGINT, right),
                LogicalType.BIGINT
        );
    }

    private TableCatalogEntry table() {
        return table(TypeName.BIGINT);
    }

    private TableCatalogEntry table(TypeName columnType) {
        return table("people", new ColumnDefinition("id", columnType));
    }

    private TableCatalogEntry table(String name, ColumnDefinition... columns) {
        Catalog catalog = new Catalog();
        Transaction transaction = new TransactionManager().startTransaction();
        return catalog.createTable(
                transaction,
                new QualifiedName(List.of(name)),
                List.of(columns)
        );
    }

    private static final class IncrementOneLiterals extends BoundExpressionRewriter {
        @Override
        protected BoundExpression visitLiteral(BoundLiteralExpression literal) {
            if (literal.logicalType().equals(LogicalType.BIGINT) && Long.valueOf(1L).equals(literal.value())) {
                return new BoundLiteralExpression(LogicalType.BIGINT, 2L);
            }
            return literal;
        }
    }
}
