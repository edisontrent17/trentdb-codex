package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundInSubqueryExpression(BoundExpression input, BoundSelectStatement subquery, boolean negated)
        implements BoundExpression {
    public LogicalType logicalType() {
        return LogicalType.BOOLEAN;
    }
}
