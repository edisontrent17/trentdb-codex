package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

/** A type-agnostic, non-nullable SQL {@code IS [NOT] NULL} predicate. */
public record BoundNullCheckExpression(BoundExpression expression, boolean negated) implements BoundExpression {
    public LogicalType logicalType() {
        return LogicalType.BOOLEAN;
    }
}
