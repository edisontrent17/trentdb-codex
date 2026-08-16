package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundInsertStatement;

public record LogicalInsert(BoundInsertStatement statement) implements LogicalOperator {
    @Override public LogicalOperatorType type() { return LogicalOperatorType.LOGICAL_INSERT; }
}
