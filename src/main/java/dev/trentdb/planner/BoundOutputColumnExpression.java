package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundOutputColumnExpression(String name, int ordinal, LogicalType logicalType) implements BoundExpression {
}
