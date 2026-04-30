package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundLiteralExpression(LogicalType logicalType, Object value) implements BoundExpression {
}
