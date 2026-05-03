package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundTableRef;

public record LogicalJoin(BoundTableRef left, BoundTableRef right, BoundExpression condition) implements LogicalOperator {
    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_COMPARISON_JOIN;
    }
}
