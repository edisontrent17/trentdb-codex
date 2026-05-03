package dev.trentdb.planner;

public record BoundJoinRef(BoundTableRef left, BoundTableRef right, BoundExpression condition) implements BoundFrom {
}
