package dev.trentdb.planner;

import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundCaseExpression(
        BoundExpression baseExpression,
        List<WhenClause> branches,
        BoundExpression elseExpression,
        LogicalType logicalType
) implements BoundExpression {
    public BoundCaseExpression {
        branches = List.copyOf(branches);
    }

    /** Constructs a searched CASE whose branch conditions are BOOLEAN expressions. */
    public BoundCaseExpression(List<WhenClause> branches, BoundExpression elseExpression, LogicalType logicalType) {
        this(null, branches, elseExpression, logicalType);
    }

    public boolean isSimpleCase() {
        return baseExpression != null;
    }

    public record WhenClause(BoundExpression condition, BoundExpression result) {
    }
}
