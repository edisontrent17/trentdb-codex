package dev.duckdbjava.ast;

public record UnaryExpression(UnaryOperator operator, Expression expression) implements Expression {
}
