package dev.trentdb.planner;

public record BoundJoinRef(BoundFrom left, BoundTableRef right, BoundExpression condition) implements BoundFrom {
}
