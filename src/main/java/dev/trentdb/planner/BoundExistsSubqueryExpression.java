package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundExistsSubqueryExpression(
        BoundSelectStatement subquery,
        int localColumnCount,
        List<CorrelatedColumn> correlatedColumns
) implements BoundExpression {
    public BoundExistsSubqueryExpression {
        correlatedColumns = List.copyOf(correlatedColumns);
    }

    public LogicalType logicalType() {
        return LogicalType.BOOLEAN;
    }

    public boolean isCorrelated() {
        return !correlatedColumns.isEmpty();
    }

    public record CorrelatedColumn(String name, LogicalType logicalType, int outerOrdinal) {
    }
}
