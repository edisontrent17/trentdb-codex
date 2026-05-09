package dev.trentdb.planner.logical;

public sealed interface LogicalOperator permits LogicalAggregate, LogicalDependentJoin, LogicalExplain, LogicalFilter,
        LogicalGet, LogicalLimit, LogicalJoin, LogicalOrder, LogicalProjection {
    LogicalOperatorType type();
}
