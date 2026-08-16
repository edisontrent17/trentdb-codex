package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundCreateIndexStatement;

public record LogicalCreateIndex(BoundCreateIndexStatement statement) implements LogicalOperator {
    @Override public LogicalOperatorType type() { return LogicalOperatorType.LOGICAL_CREATE_INDEX; }
}
