package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundBetweenExpression(
        BoundExpression input,
        BoundExpression lower,
        BoundExpression upper,
        boolean negated
) implements BoundExpression {
    public BoundBetweenExpression(BoundExpression input, BoundExpression lower, BoundExpression upper) {
        this(input, lower, upper, false);
    }

    public LogicalType logicalType() {
        return LogicalType.BOOLEAN;
    }
}
