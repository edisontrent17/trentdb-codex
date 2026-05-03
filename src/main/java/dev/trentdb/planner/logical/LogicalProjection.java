package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundExpression;

import java.util.List;

public record LogicalProjection(List<BoundExpression> expressions, List<String> names, LogicalOperator child) implements LogicalOperator {
    public LogicalProjection {
        expressions = List.copyOf(expressions);
        names = List.copyOf(names);
    }

    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_PROJECTION;
    }
}
