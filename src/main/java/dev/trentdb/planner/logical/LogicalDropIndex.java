package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundDropIndexStatement;

public record LogicalDropIndex(BoundDropIndexStatement statement) implements LogicalOperator {
    @Override public LogicalOperatorType type() { return LogicalOperatorType.LOGICAL_DROP_INDEX; }
}
