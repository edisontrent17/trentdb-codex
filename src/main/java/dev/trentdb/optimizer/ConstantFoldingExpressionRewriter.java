package dev.trentdb.optimizer;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.util.List;

final class ConstantFoldingExpressionRewriter extends BoundExpressionRewriter {
    private final DataChunk scalarInput = new DataChunk(
            List.of("__constant_fold_row"),
            List.of(new Vector(LogicalType.BIGINT, 1))
    );
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor(new StorageManager());

    @Override
    protected BoundExpression visitBetween(BoundBetweenExpression between) {
        return fold(super.visitBetween(between));
    }

    @Override
    protected BoundExpression visitBinary(BoundBinaryExpression binary) {
        return fold(super.visitBinary(binary));
    }

    @Override
    protected BoundExpression visitCase(BoundCaseExpression caseExpression) {
        return fold(super.visitCase(caseExpression));
    }

    @Override
    protected BoundExpression visitCast(BoundCastExpression cast) {
        return fold(super.visitCast(cast));
    }

    @Override
    protected BoundExpression visitFunction(BoundFunctionExpression function) {
        return fold(super.visitFunction(function));
    }

    @Override
    protected BoundExpression visitIn(BoundInExpression in) {
        return fold(super.visitIn(in));
    }

    private BoundExpression fold(BoundExpression expression) {
        if (expression instanceof BoundLiteralExpression || !isFoldable(expression)) {
            return expression;
        }
        try {
            Vector vector = expressionExecutor.execute(expression, scalarInput);
            return new BoundLiteralExpression(vector.logicalType(), vector.boxedValue(0));
        } catch (RuntimeException ignored) {
            return expression;
        }
    }

    private boolean isFoldable(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression ignored -> false;
            case BoundBetweenExpression between -> isFoldable(between.input())
                    && isFoldable(between.lower())
                    && isFoldable(between.upper());
            case BoundBinaryExpression binary -> isFoldable(binary.left()) && isFoldable(binary.right());
            case BoundCaseExpression caseExpression -> isFoldableCase(caseExpression);
            case BoundCastExpression cast -> isFoldable(cast.child());
            case BoundColumnRefExpression ignored -> false;
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundFunctionExpression function -> allFoldable(function.arguments());
            case BoundInExpression in -> isFoldable(in.input()) && allFoldable(in.candidates());
            case BoundInSubqueryExpression ignored -> false;
            case BoundIntervalExpression ignored -> true;
            case BoundLiteralExpression ignored -> true;
            case BoundOutputColumnExpression ignored -> false;
            case BoundSubqueryExpression ignored -> false;
        };
    }

    private boolean isFoldableCase(BoundCaseExpression caseExpression) {
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (!isFoldable(branch.condition()) || !isFoldable(branch.result())) {
                return false;
            }
        }
        return isFoldable(caseExpression.elseExpression());
    }

    private boolean allFoldable(List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (!isFoldable(expression)) {
                return false;
            }
        }
        return true;
    }
}
