package dev.trentdb.planner.logical;

public record LogicalLimit(long limit, LogicalOperator child) implements LogicalOperator {
}
