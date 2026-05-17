package dev.trentdb.optimizer;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.JoinType;
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
import dev.trentdb.planner.logical.LogicalAggregate;
import dev.trentdb.planner.logical.LogicalDependentJoin;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalJoin;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalOrder;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

final class FilterPushdown {
    LogicalOperator optimize(LogicalOperator operator) {
        return push(operator);
    }

    private LogicalOperator push(LogicalOperator operator) {
        return switch (operator) {
            case LogicalAggregate aggregate -> pushAggregate(aggregate);
            case LogicalDependentJoin join -> pushDependentJoin(join);
            case LogicalExplain explain -> pushExplain(explain);
            case LogicalFilter filter -> pushFilter(filter.predicate(), push(filter.child()));
            case LogicalGet get -> get;
            case LogicalJoin join -> pushJoin(join);
            case LogicalLimit limit -> pushLimit(limit);
            case LogicalOrder order -> pushOrder(order);
            case LogicalProjection projection -> pushProjection(projection);
        };
    }

    private LogicalOperator pushAggregate(LogicalAggregate aggregate) {
        LogicalOperator child = push(aggregate.child());
        return child == aggregate.child()
                ? aggregate
                : new LogicalAggregate(aggregate.groups(), aggregate.selectList(), aggregate.selectNames(), child);
    }

    private LogicalOperator pushDependentJoin(LogicalDependentJoin join) {
        LogicalOperator child = push(join.child());
        return child == join.child()
                ? join
                : new LogicalDependentJoin(child, join.subquery(), join.scalarSubquery(), join.marker(), join.kind());
    }

    private LogicalOperator pushExplain(LogicalExplain explain) {
        LogicalOperator child = push(explain.child());
        return child == explain.child() ? explain : new LogicalExplain(child);
    }

    private LogicalOperator pushJoin(LogicalJoin join) {
        LogicalOperator left = push(join.left());
        LogicalOperator right = push(join.right());
        return left == join.left() && right == join.right()
                ? join
                : new LogicalJoin(left, right, join.condition(), join.joinType());
    }

    private LogicalOperator pushLimit(LogicalLimit limit) {
        LogicalOperator child = push(limit.child());
        return child == limit.child() ? limit : new LogicalLimit(limit.limit(), child);
    }

    private LogicalOperator pushOrder(LogicalOrder order) {
        LogicalOperator child = push(order.child());
        return child == order.child() ? order : new LogicalOrder(order.orders(), child);
    }

    private LogicalOperator pushProjection(LogicalProjection projection) {
        LogicalOperator child = push(projection.child());
        return child == projection.child()
                ? projection
                : new LogicalProjection(projection.expressions(), projection.names(), child);
    }

    private LogicalOperator pushFilter(BoundExpression predicate, LogicalOperator child) {
        if (child instanceof LogicalFilter filter) {
            return pushFilter(and(filter.predicate(), predicate), filter.child());
        }
        if (child instanceof LogicalOrder order) {
            return new LogicalOrder(order.orders(), pushFilter(predicate, order.child()));
        }
        if (child instanceof LogicalProjection projection && !containsSubquery(predicate)) {
            BoundExpression pushedPredicate = substituteProjection(predicate, projection.expressions());
            LogicalOperator pushedChild = pushFilter(pushedPredicate, projection.child());
            return new LogicalProjection(projection.expressions(), projection.names(), pushedChild);
        }
        if (child instanceof LogicalJoin join && join.joinType() == JoinType.INNER && !containsSubquery(predicate)) {
            return pushInnerJoinFilter(predicate, join);
        }
        return new LogicalFilter(predicate, child);
    }

    private LogicalOperator pushInnerJoinFilter(BoundExpression predicate, LogicalJoin join) {
        int leftCount = outputColumnCount(join.left());
        int rightCount = outputColumnCount(join.right());
        ArrayList<BoundExpression> leftPredicates = new ArrayList<>();
        ArrayList<BoundExpression> rightPredicates = new ArrayList<>();
        ArrayList<BoundExpression> residualPredicates = new ArrayList<>();
        ArrayList<BoundExpression> conjuncts = new ArrayList<>();
        flattenConjuncts(predicate, conjuncts);

        for (BoundExpression conjunct : conjuncts) {
            PredicateScope scope = scopeOf(conjunct, leftCount, rightCount);
            if (scope == PredicateScope.LEFT) {
                leftPredicates.add(conjunct);
            } else if (scope == PredicateScope.RIGHT) {
                rightPredicates.add(rewriteForJoinSide(conjunct, leftCount));
            } else {
                residualPredicates.add(conjunct);
            }
        }

        LogicalOperator left = pushFilterIfPresent(combineConjuncts(leftPredicates), join.left());
        LogicalOperator right = pushFilterIfPresent(combineConjuncts(rightPredicates), join.right());
        LogicalJoin pushedJoin = new LogicalJoin(left, right, join.condition(), join.joinType());
        BoundExpression residual = combineConjuncts(residualPredicates);
        return residual == null ? pushedJoin : new LogicalFilter(residual, pushedJoin);
    }

    private LogicalOperator pushFilterIfPresent(BoundExpression predicate, LogicalOperator child) {
        return predicate == null ? child : pushFilter(predicate, child);
    }

    private BoundExpression substituteProjection(BoundExpression expression, List<BoundExpression> projectionExpressions) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> new BoundAggregateExpression(
                    aggregate.function(),
                    substituteProjectionList(aggregate.arguments(), projectionExpressions),
                    aggregate.starArgument(),
                    aggregate.distinct()
            );
            case BoundBetweenExpression between -> new BoundBetweenExpression(
                    substituteProjection(between.input(), projectionExpressions),
                    substituteProjection(between.lower(), projectionExpressions),
                    substituteProjection(between.upper(), projectionExpressions)
            );
            case BoundBinaryExpression binary -> new BoundBinaryExpression(
                    substituteProjection(binary.left(), projectionExpressions),
                    binary.operator(),
                    substituteProjection(binary.right(), projectionExpressions),
                    binary.logicalType()
            );
            case BoundCaseExpression caseExpression -> substituteProjectionCase(caseExpression, projectionExpressions);
            case BoundCastExpression cast -> new BoundCastExpression(
                    substituteProjection(cast.child(), projectionExpressions),
                    cast.logicalType()
            );
            case BoundColumnRefExpression column -> projectionExpressions.get(column.ordinal());
            case BoundExistsSubqueryExpression exists -> exists;
            case BoundFunctionExpression function -> new BoundFunctionExpression(
                    function.function(),
                    substituteProjectionList(function.arguments(), projectionExpressions)
            );
            case BoundInExpression in -> new BoundInExpression(
                    substituteProjection(in.input(), projectionExpressions),
                    substituteProjectionList(in.candidates(), projectionExpressions),
                    in.negated()
            );
            case BoundInSubqueryExpression in -> in;
            case BoundIntervalExpression interval -> interval;
            case BoundLiteralExpression literal -> literal;
            case BoundOutputColumnExpression output -> projectionExpressions.get(output.ordinal());
            case BoundSubqueryExpression subquery -> subquery;
        };
    }

    private ArrayList<BoundExpression> substituteProjectionList(
            List<BoundExpression> expressions,
            List<BoundExpression> projectionExpressions
    ) {
        ArrayList<BoundExpression> substituted = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            substituted.add(substituteProjection(expression, projectionExpressions));
        }
        return substituted;
    }

    private BoundCaseExpression substituteProjectionCase(
            BoundCaseExpression caseExpression,
            List<BoundExpression> projectionExpressions
    ) {
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            branches.add(new BoundCaseExpression.WhenClause(
                    substituteProjection(branch.condition(), projectionExpressions),
                    substituteProjection(branch.result(), projectionExpressions)
            ));
        }
        return new BoundCaseExpression(
                branches,
                substituteProjection(caseExpression.elseExpression(), projectionExpressions),
                caseExpression.logicalType()
        );
    }

    private PredicateScope scopeOf(BoundExpression expression, int leftColumnCount, int rightColumnCount) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> scopeOfList(aggregate.arguments(), leftColumnCount, rightColumnCount);
            case BoundBetweenExpression between -> combineScope(
                    combineScope(
                            scopeOf(between.input(), leftColumnCount, rightColumnCount),
                            scopeOf(between.lower(), leftColumnCount, rightColumnCount)
                    ),
                    scopeOf(between.upper(), leftColumnCount, rightColumnCount)
            );
            case BoundBinaryExpression binary -> combineScope(
                    scopeOf(binary.left(), leftColumnCount, rightColumnCount),
                    scopeOf(binary.right(), leftColumnCount, rightColumnCount)
            );
            case BoundCaseExpression caseExpression -> scopeOfCase(caseExpression, leftColumnCount, rightColumnCount);
            case BoundCastExpression cast -> scopeOf(cast.child(), leftColumnCount, rightColumnCount);
            case BoundColumnRefExpression column -> columnScope(column.ordinal(), leftColumnCount, rightColumnCount);
            case BoundExistsSubqueryExpression ignored -> PredicateScope.MIXED;
            case BoundFunctionExpression function -> scopeOfList(function.arguments(), leftColumnCount, rightColumnCount);
            case BoundInExpression in -> combineScope(
                    scopeOf(in.input(), leftColumnCount, rightColumnCount),
                    scopeOfList(in.candidates(), leftColumnCount, rightColumnCount)
            );
            case BoundInSubqueryExpression ignored -> PredicateScope.MIXED;
            case BoundIntervalExpression ignored -> PredicateScope.NONE;
            case BoundLiteralExpression ignored -> PredicateScope.NONE;
            case BoundOutputColumnExpression output -> columnScope(output.ordinal(), leftColumnCount, rightColumnCount);
            case BoundSubqueryExpression ignored -> PredicateScope.MIXED;
        };
    }

    private PredicateScope scopeOfList(
            List<BoundExpression> expressions,
            int leftColumnCount,
            int rightColumnCount
    ) {
        PredicateScope scope = PredicateScope.NONE;
        for (BoundExpression expression : expressions) {
            scope = combineScope(scope, scopeOf(expression, leftColumnCount, rightColumnCount));
        }
        return scope;
    }

    private PredicateScope scopeOfCase(
            BoundCaseExpression caseExpression,
            int leftColumnCount,
            int rightColumnCount
    ) {
        PredicateScope scope = scopeOf(caseExpression.elseExpression(), leftColumnCount, rightColumnCount);
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            scope = combineScope(scope, scopeOf(branch.condition(), leftColumnCount, rightColumnCount));
            scope = combineScope(scope, scopeOf(branch.result(), leftColumnCount, rightColumnCount));
        }
        return scope;
    }

    private PredicateScope columnScope(int ordinal, int leftColumnCount, int rightColumnCount) {
        if (ordinal >= 0 && ordinal < leftColumnCount) {
            return PredicateScope.LEFT;
        }
        int rightOrdinal = ordinal - leftColumnCount;
        if (rightOrdinal >= 0 && rightOrdinal < rightColumnCount) {
            return PredicateScope.RIGHT;
        }
        return PredicateScope.MIXED;
    }

    private PredicateScope combineScope(PredicateScope left, PredicateScope right) {
        if (left == PredicateScope.NONE) {
            return right;
        }
        if (right == PredicateScope.NONE) {
            return left;
        }
        return left == right ? left : PredicateScope.MIXED;
    }

    private BoundExpression rewriteForJoinSide(BoundExpression expression, int leftColumnCount) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> new BoundAggregateExpression(
                    aggregate.function(),
                    rewriteListForJoinSide(aggregate.arguments(), leftColumnCount),
                    aggregate.starArgument(),
                    aggregate.distinct()
            );
            case BoundBetweenExpression between -> new BoundBetweenExpression(
                    rewriteForJoinSide(between.input(), leftColumnCount),
                    rewriteForJoinSide(between.lower(), leftColumnCount),
                    rewriteForJoinSide(between.upper(), leftColumnCount)
            );
            case BoundBinaryExpression binary -> new BoundBinaryExpression(
                    rewriteForJoinSide(binary.left(), leftColumnCount),
                    binary.operator(),
                    rewriteForJoinSide(binary.right(), leftColumnCount),
                    binary.logicalType()
            );
            case BoundCaseExpression caseExpression -> rewriteCaseForJoinSide(caseExpression, leftColumnCount);
            case BoundCastExpression cast -> new BoundCastExpression(
                    rewriteForJoinSide(cast.child(), leftColumnCount),
                    cast.logicalType()
            );
            case BoundColumnRefExpression column -> new BoundColumnRefExpression(
                    column.column(),
                    column.ordinal() - leftColumnCount
            );
            case BoundExistsSubqueryExpression exists -> exists;
            case BoundFunctionExpression function -> new BoundFunctionExpression(
                    function.function(),
                    rewriteListForJoinSide(function.arguments(), leftColumnCount)
            );
            case BoundInExpression in -> new BoundInExpression(
                    rewriteForJoinSide(in.input(), leftColumnCount),
                    rewriteListForJoinSide(in.candidates(), leftColumnCount),
                    in.negated()
            );
            case BoundInSubqueryExpression in -> in;
            case BoundIntervalExpression interval -> interval;
            case BoundLiteralExpression literal -> literal;
            case BoundOutputColumnExpression output -> new BoundOutputColumnExpression(
                    output.name(),
                    output.ordinal() - leftColumnCount,
                    output.logicalType()
            );
            case BoundSubqueryExpression subquery -> subquery;
        };
    }

    private ArrayList<BoundExpression> rewriteListForJoinSide(List<BoundExpression> expressions, int leftColumnCount) {
        ArrayList<BoundExpression> rewritten = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            rewritten.add(rewriteForJoinSide(expression, leftColumnCount));
        }
        return rewritten;
    }

    private BoundCaseExpression rewriteCaseForJoinSide(BoundCaseExpression caseExpression, int leftColumnCount) {
        ArrayList<BoundCaseExpression.WhenClause> branches = new ArrayList<>(caseExpression.branches().size());
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            branches.add(new BoundCaseExpression.WhenClause(
                    rewriteForJoinSide(branch.condition(), leftColumnCount),
                    rewriteForJoinSide(branch.result(), leftColumnCount)
            ));
        }
        return new BoundCaseExpression(
                branches,
                rewriteForJoinSide(caseExpression.elseExpression(), leftColumnCount),
                caseExpression.logicalType()
        );
    }

    private boolean containsSubquery(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> containsSubqueryList(aggregate.arguments());
            case BoundBetweenExpression between -> containsSubquery(between.input())
                    || containsSubquery(between.lower())
                    || containsSubquery(between.upper());
            case BoundBinaryExpression binary -> containsSubquery(binary.left()) || containsSubquery(binary.right());
            case BoundCaseExpression caseExpression -> containsSubqueryCase(caseExpression);
            case BoundCastExpression cast -> containsSubquery(cast.child());
            case BoundColumnRefExpression ignored -> false;
            case BoundExistsSubqueryExpression ignored -> true;
            case BoundFunctionExpression function -> containsSubqueryList(function.arguments());
            case BoundInExpression in -> containsSubquery(in.input()) || containsSubqueryList(in.candidates());
            case BoundInSubqueryExpression ignored -> true;
            case BoundIntervalExpression ignored -> false;
            case BoundLiteralExpression ignored -> false;
            case BoundOutputColumnExpression ignored -> false;
            case BoundSubqueryExpression ignored -> true;
        };
    }

    private boolean containsSubqueryList(List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (containsSubquery(expression)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSubqueryCase(BoundCaseExpression caseExpression) {
        if (containsSubquery(caseExpression.elseExpression())) {
            return true;
        }
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (containsSubquery(branch.condition()) || containsSubquery(branch.result())) {
                return true;
            }
        }
        return false;
    }

    private void flattenConjuncts(BoundExpression expression, List<BoundExpression> output) {
        if (expression instanceof BoundBinaryExpression binary && binary.operator() == BinaryOperator.AND) {
            flattenConjuncts(binary.left(), output);
            flattenConjuncts(binary.right(), output);
            return;
        }
        output.add(expression);
    }

    private BoundExpression combineConjuncts(List<BoundExpression> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        BoundExpression result = conjuncts.getFirst();
        for (int index = 1; index < conjuncts.size(); index++) {
            result = and(result, conjuncts.get(index));
        }
        return result;
    }

    private BoundBinaryExpression and(BoundExpression left, BoundExpression right) {
        return new BoundBinaryExpression(left, BinaryOperator.AND, right, LogicalType.BOOLEAN);
    }

    private int outputColumnCount(LogicalOperator operator) {
        return switch (operator) {
            case LogicalAggregate aggregate -> aggregate.selectList().size();
            case LogicalDependentJoin join -> outputColumnCount(join.child()) + 1;
            case LogicalExplain ignored -> 1;
            case LogicalFilter filter -> outputColumnCount(filter.child());
            case LogicalGet get -> get.projectedOrdinals().size();
            case LogicalJoin join -> outputColumnCount(join.left()) + outputColumnCount(join.right());
            case LogicalLimit limit -> outputColumnCount(limit.child());
            case LogicalOrder order -> outputColumnCount(order.child());
            case LogicalProjection projection -> projection.expressions().size();
        };
    }

    private enum PredicateScope {
        NONE,
        LEFT,
        RIGHT,
        MIXED
    }
}
