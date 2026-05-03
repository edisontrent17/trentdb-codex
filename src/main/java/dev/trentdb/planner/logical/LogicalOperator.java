package dev.trentdb.planner.logical;

public sealed interface LogicalOperator permits LogicalAggregate, LogicalExplain, LogicalFilter, LogicalGet, LogicalLimit,
        LogicalJoin, LogicalOrder, LogicalProjection {
    LogicalOperatorType type();
}
