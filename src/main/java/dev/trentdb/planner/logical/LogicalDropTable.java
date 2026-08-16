package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundDropTableStatement;

public record LogicalDropTable(BoundDropTableStatement statement) implements LogicalOperator {
    @Override public LogicalOperatorType type() { return LogicalOperatorType.LOGICAL_DROP_TABLE; }
}
