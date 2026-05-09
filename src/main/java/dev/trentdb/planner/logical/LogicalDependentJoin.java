package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;

public record LogicalDependentJoin(
        LogicalOperator child,
        BoundExistsSubqueryExpression subquery,
        BoundColumnRefExpression marker
) implements LogicalOperator {
    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_DELIM_JOIN;
    }
}
