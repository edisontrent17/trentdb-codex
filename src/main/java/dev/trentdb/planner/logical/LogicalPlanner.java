package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundExplainStatement;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundStatement;
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
        LogicalOperator root = new LogicalGet(statement.from());
        if (statement.where() != null) {
            root = new LogicalFilter(statement.where(), root);
        }
        if (!statement.orderBy().isEmpty()) {
            root = new LogicalOrder(statement.orderBy(), root);
        }
        root = new LogicalProjection(statement.selectList(), statement.selectNames(), root);
        if (statement.limit() != null) {
            root = new LogicalLimit(statement.limit(), root);
        }
        return root;
    }
}
