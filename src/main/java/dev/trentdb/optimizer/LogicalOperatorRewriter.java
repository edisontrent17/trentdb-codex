package dev.trentdb.optimizer;

import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundOrderByItem;
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

import java.util.ArrayList;
import java.util.List;

class LogicalOperatorRewriter {
    private final BoundExpressionRewriter expressionRewriter;
    private int operatorsVisited;
    private int operatorsRebuilt;
    private int expressionListsRebuilt;

    LogicalOperatorRewriter(BoundExpressionRewriter expressionRewriter) {
        this.expressionRewriter = expressionRewriter;
    }

    LogicalOperator rewrite(LogicalOperator operator) {
        operatorsVisited++;
        LogicalOperator rewritten = switch (operator) {
            case LogicalAggregate aggregate -> rewriteAggregate(aggregate);
            case LogicalDependentJoin join -> rewriteDependentJoin(join);
            case LogicalExplain explain -> rewriteExplain(explain);
            case LogicalFilter filter -> rewriteFilter(filter);
            case LogicalGet get -> get;
            case LogicalJoin join -> rewriteJoin(join);
            case LogicalLimit limit -> rewriteLimit(limit);
            case LogicalOrder order -> rewriteOrder(order);
            case LogicalProjection projection -> rewriteProjection(projection);
        };
        if (rewritten != operator) {
            operatorsRebuilt++;
        }
        return rewritten;
    }

    private LogicalOperator rewriteAggregate(LogicalAggregate aggregate) {
        List<BoundExpression> groups = rewriteExpressions(aggregate.groups());
        List<BoundExpression> selectList = rewriteExpressions(aggregate.selectList());
        LogicalOperator child = rewrite(aggregate.child());
        if (groups == aggregate.groups() && selectList == aggregate.selectList() && child == aggregate.child()) {
            return aggregate;
        }
        return new LogicalAggregate(groups, selectList, aggregate.selectNames(), child);
    }

    private LogicalOperator rewriteDependentJoin(LogicalDependentJoin join) {
        LogicalOperator child = rewrite(join.child());
        return child == join.child()
                ? join
                : new LogicalDependentJoin(child, join.subquery(), join.scalarSubquery(), join.marker(), join.kind());
    }

    private LogicalOperator rewriteExplain(LogicalExplain explain) {
        LogicalOperator child = rewrite(explain.child());
        return child == explain.child() ? explain : new LogicalExplain(child);
    }

    private LogicalOperator rewriteFilter(LogicalFilter filter) {
        BoundExpression predicate = expressionRewriter.rewrite(filter.predicate());
        LogicalOperator child = rewrite(filter.child());
        if (predicate == filter.predicate() && child == filter.child()) {
            return filter;
        }
        return new LogicalFilter(predicate, child);
    }

    private LogicalOperator rewriteJoin(LogicalJoin join) {
        LogicalOperator left = rewrite(join.left());
        LogicalOperator right = rewrite(join.right());
        BoundExpression condition = join.condition() == null ? null : expressionRewriter.rewrite(join.condition());
        if (left == join.left() && right == join.right() && condition == join.condition()) {
            return join;
        }
        return new LogicalJoin(left, right, condition, join.joinType());
    }

    private LogicalOperator rewriteLimit(LogicalLimit limit) {
        LogicalOperator child = rewrite(limit.child());
        return child == limit.child() ? limit : new LogicalLimit(limit.limit(), child);
    }

    private LogicalOperator rewriteOrder(LogicalOrder order) {
        List<BoundOrderByItem> orders = expressionRewriter.rewriteOrderByItems(order.orders());
        LogicalOperator child = rewrite(order.child());
        if (orders == order.orders() && child == order.child()) {
            return order;
        }
        return new LogicalOrder(orders, child);
    }

    private LogicalOperator rewriteProjection(LogicalProjection projection) {
        List<BoundExpression> expressions = rewriteExpressions(projection.expressions());
        LogicalOperator child = rewrite(projection.child());
        if (expressions == projection.expressions() && child == projection.child()) {
            return projection;
        }
        return new LogicalProjection(expressions, projection.names(), child);
    }

    private List<BoundExpression> rewriteExpressions(List<BoundExpression> expressions) {
        for (int index = 0; index < expressions.size(); index++) {
            BoundExpression rewritten = expressionRewriter.rewrite(expressions.get(index));
            if (rewritten != expressions.get(index)) {
                expressionListsRebuilt++;
                return rewriteChangedExpressions(expressions, index, rewritten);
            }
        }
        return expressions;
    }

    private List<BoundExpression> rewriteChangedExpressions(
            List<BoundExpression> expressions,
            int changedIndex,
            BoundExpression changedExpression
    ) {
        ArrayList<BoundExpression> rewritten = new ArrayList<>(expressions);
        rewritten.set(changedIndex, changedExpression);
        for (int index = changedIndex + 1; index < rewritten.size(); index++) {
            rewritten.set(index, expressionRewriter.rewrite(rewritten.get(index)));
        }
        return rewritten;
    }

    int operatorsVisited() {
        return operatorsVisited;
    }

    int operatorsRebuilt() {
        return operatorsRebuilt;
    }

    int expressionListsRebuilt() {
        return expressionListsRebuilt;
    }
}
