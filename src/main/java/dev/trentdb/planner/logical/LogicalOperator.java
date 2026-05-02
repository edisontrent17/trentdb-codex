package dev.trentdb.planner.logical;

public sealed interface LogicalOperator permits LogicalExplain, LogicalFilter, LogicalGet, LogicalLimit, LogicalOrder, LogicalProjection {
}
