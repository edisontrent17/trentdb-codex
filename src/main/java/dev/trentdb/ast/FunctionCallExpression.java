package dev.trentdb.ast;

import java.util.List;

public record FunctionCallExpression(
        String name,
        List<Expression> arguments,
        boolean starArgument,
        boolean distinct
) implements Expression {
    public FunctionCallExpression {
        arguments = List.copyOf(arguments);
    }
}
