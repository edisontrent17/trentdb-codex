package dev.trentdb.planner.logical;

public record LogicalExplain(LogicalOperator child) implements LogicalOperator {
    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_EXPLAIN;
    }
}
