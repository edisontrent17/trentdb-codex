package dev.trentdb.planner;

import dev.trentdb.ast.SortDirection;

public record BoundOrderByItem(BoundExpression expression, SortDirection direction) {
}
