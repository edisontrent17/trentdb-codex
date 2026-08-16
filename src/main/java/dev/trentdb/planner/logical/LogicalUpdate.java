package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundUpdateStatement;

public record LogicalUpdate(BoundUpdateStatement statement) implements LogicalOperator {
    @Override public LogicalOperatorType type() { return LogicalOperatorType.LOGICAL_UPDATE; }
}
