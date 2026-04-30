package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundExpression;

public record LogicalFilter(BoundExpression predicate, LogicalOperator child) implements LogicalOperator {
}
