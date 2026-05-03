package dev.trentdb.planner;

import dev.trentdb.function.AggregateFunction;
import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundAggregateExpression(
        AggregateFunction function,
        List<BoundExpression> arguments,
        boolean starArgument
) implements BoundExpression {
    public BoundAggregateExpression {
        arguments = List.copyOf(arguments);
    }

    public String name() {
        return function.name();
    }

    public LogicalType logicalType() {
        return function.returnType();
    }
}
