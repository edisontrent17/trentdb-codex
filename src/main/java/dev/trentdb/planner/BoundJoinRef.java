package dev.trentdb.planner;

import dev.trentdb.ast.JoinType;

public record BoundJoinRef(
        BoundFrom left,
        BoundTableRef right,
        BoundExpression condition,
        JoinType type
) implements BoundFrom {
}
