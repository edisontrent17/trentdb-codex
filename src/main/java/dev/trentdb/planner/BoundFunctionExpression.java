package dev.trentdb.planner;

import dev.trentdb.function.ScalarFunction;
import dev.trentdb.types.LogicalType;

import java.util.List;

public record BoundFunctionExpression(ScalarFunction function, List<BoundExpression> arguments) implements BoundExpression {
    public BoundFunctionExpression {
        arguments = List.copyOf(arguments);
    }

    public String name() {
        return function.name();
    }

    public LogicalType logicalType() {
        return function.returnType();
    }
}
