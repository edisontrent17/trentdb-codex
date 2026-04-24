package dev.duckdbjava.ast;

public record NullCheckExpression(Expression expression, boolean negated) implements Expression {
}
