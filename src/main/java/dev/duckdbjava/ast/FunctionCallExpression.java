package dev.duckdbjava.ast;

import java.util.List;

public record FunctionCallExpression(String name, List<Expression> arguments, boolean starArgument) implements Expression {
}
