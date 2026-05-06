package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

public record BoundSubqueryExpression(BoundSelectStatement subquery, LogicalType logicalType) implements BoundExpression {
}
