package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundExplainStatement;
import dev.trentdb.planner.BoundFrom;
import dev.trentdb.planner.BoundJoinRef;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundStatement;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.planner.BinderException;

public final class LogicalPlanner {
    public LogicalOperator plan(BoundStatement statement) {
        if (statement instanceof BoundExplainStatement explain) {
            return new LogicalExplain(plan(explain.statement()));
        }
        if (statement instanceof BoundSelectStatement select) {
            return planSelect(select);
        }
        throw new BinderException("Unsupported bound statement for logical planning: " + statement.getClass().getSimpleName());
    }

    private LogicalOperator planSelect(BoundSelectStatement statement) {
        LogicalOperator root = planFrom(statement.from());
        if (statement.where() != null) {
            root = new LogicalFilter(statement.where(), root);
        }
        if (statement.isAggregateQuery()) {
            root = new LogicalAggregate(statement.groupBy(), statement.selectList(), statement.selectNames(), root);
        }
        if (!statement.orderBy().isEmpty()) {
            root = new LogicalOrder(statement.orderBy(), root);
        }
        if (!statement.isAggregateQuery()) {
            root = new LogicalProjection(statement.selectList(), statement.selectNames(), root);
        }
        if (statement.limit() != null) {
            root = new LogicalLimit(statement.limit(), root);
        }
        return root;
    }

    private LogicalOperator planFrom(BoundFrom from) {
        if (from instanceof BoundTableRef tableRef) {
            return new LogicalGet(tableRef);
        }
        if (from instanceof BoundJoinRef joinRef) {
            return new LogicalJoin(joinRef.left(), joinRef.right(), joinRef.condition());
        }
        throw new BinderException("Unsupported bound FROM source: " + from.getClass().getSimpleName());
    }
}
