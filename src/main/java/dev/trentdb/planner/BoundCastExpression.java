package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundCastExpression(BoundExpression child, LogicalType logicalType) implements BoundExpression {
}
