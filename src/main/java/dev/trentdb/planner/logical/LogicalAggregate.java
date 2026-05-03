package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundExpression;

import java.util.List;

public record LogicalAggregate(
        List<BoundExpression> groups,
        List<BoundExpression> selectList,
        List<String> selectNames,
        LogicalOperator child
) implements LogicalOperator {
    public LogicalAggregate {
        groups = List.copyOf(groups);
        selectList = List.copyOf(selectList);
        selectNames = List.copyOf(selectNames);
    }

    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_AGGREGATE_AND_GROUP_BY;
    }
}
