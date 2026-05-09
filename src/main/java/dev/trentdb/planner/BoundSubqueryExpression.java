package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundSubqueryExpression(
        BoundSelectStatement subquery,
        LogicalType logicalType,
        int localColumnCount,
        List<BoundExistsSubqueryExpression.CorrelatedColumn> correlatedColumns
) implements BoundExpression {
    public BoundSubqueryExpression {
        correlatedColumns = List.copyOf(correlatedColumns);
    }

    public boolean isCorrelated() {
        return !correlatedColumns.isEmpty();
    }
}
