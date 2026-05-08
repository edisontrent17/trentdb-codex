package dev.trentdb.planner.logical;

import dev.trentdb.ast.JoinType;
import dev.trentdb.planner.BoundExpression;

public record LogicalJoin(
        LogicalOperator left,
        LogicalOperator right,
        BoundExpression condition,
        JoinType joinType
) implements LogicalOperator {
    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_COMPARISON_JOIN;
    }
}
