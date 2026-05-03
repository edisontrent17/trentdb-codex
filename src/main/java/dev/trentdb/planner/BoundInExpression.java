package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundInExpression(BoundExpression input, List<BoundExpression> candidates, boolean negated)
        implements BoundExpression {
    public BoundInExpression {
        candidates = List.copyOf(candidates);
    }

    public LogicalType logicalType() {
        return LogicalType.BOOLEAN;
    }
}
