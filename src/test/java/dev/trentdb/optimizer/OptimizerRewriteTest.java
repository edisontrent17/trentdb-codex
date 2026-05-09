package dev.trentdb.optimizer;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.types.LogicalType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void optimizerCollectsImmutableRewriteMetricsWhenEnabled() {
        LogicalGet get = new LogicalGet(new BoundTableRef(table(), null));
        LogicalProjection projection = new LogicalProjection(
                List.of(binaryLiteralExpression(1L, 3L)),
                List.of("value"),
                get
        );
        Optimizer optimizer = new Optimizer(true);

        LogicalOperator optimized = optimizer.optimize(projection);

        Optimizer.Metrics metrics = optimizer.metrics();
        assertSame(projection, optimized);
        assertEquals(2, metrics.logicalOperatorsVisited());
        assertEquals(0, metrics.logicalOperatorsRebuilt());
        assertEquals(3, metrics.boundExpressionsVisited());
        assertEquals(0, metrics.boundExpressionsRebuilt());
        assertEquals(0, metrics.expressionListsRebuilt());
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
        Catalog catalog = new Catalog();
        Transaction transaction = new TransactionManager().startTransaction();
        return catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(new ColumnDefinition("id", TypeName.BIGINT))
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
