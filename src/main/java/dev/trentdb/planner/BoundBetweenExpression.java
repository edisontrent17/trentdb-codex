package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundBetweenExpression(
        BoundExpression input,
        BoundExpression lower,
        BoundExpression upper
) implements BoundExpression {
    public LogicalType logicalType() {
        return LogicalType.BOOLEAN;
    }
}
