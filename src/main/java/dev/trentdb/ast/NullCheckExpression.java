package dev.trentdb.ast;

public record NullCheckExpression(Expression expression, boolean negated) implements Expression {
}
