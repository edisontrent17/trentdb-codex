package dev.trentdb.planner.logical;

public record LogicalExplain(LogicalOperator child) implements LogicalOperator {
}
