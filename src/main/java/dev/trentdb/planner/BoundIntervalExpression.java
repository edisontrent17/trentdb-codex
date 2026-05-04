package dev.trentdb.planner;

import dev.trentdb.ast.IntervalUnit;

public record BoundIntervalExpression(long amount, IntervalUnit unit) implements BoundExpression {
}
