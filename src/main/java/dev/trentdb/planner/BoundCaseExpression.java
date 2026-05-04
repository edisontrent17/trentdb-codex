package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundCaseExpression(
        List<WhenClause> branches,
        BoundExpression elseExpression,
        LogicalType logicalType
) implements BoundExpression {
    public BoundCaseExpression {
        branches = List.copyOf(branches);
    }

    public record WhenClause(BoundExpression condition, BoundExpression result) {
    }
}
