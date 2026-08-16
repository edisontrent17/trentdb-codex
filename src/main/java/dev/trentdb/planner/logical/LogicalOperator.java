package dev.trentdb.planner.logical;

public sealed interface LogicalOperator permits LogicalAggregate, LogicalCreateTable, LogicalDropTable, LogicalCreateIndex, LogicalDropIndex, LogicalDependentJoin, LogicalInsert, LogicalDelete, LogicalUpdate, LogicalExplain, LogicalFilter,
        LogicalGet, LogicalLimit, LogicalJoin, LogicalOrder, LogicalProjection, LogicalSetOperation {
    LogicalOperatorType type();
}
