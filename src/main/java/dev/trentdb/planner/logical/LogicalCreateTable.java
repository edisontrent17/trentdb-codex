package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundCreateTableStatement;

public record LogicalCreateTable(BoundCreateTableStatement statement) implements LogicalOperator {
    @Override public LogicalOperatorType type() { return LogicalOperatorType.LOGICAL_CREATE_TABLE; }
}
