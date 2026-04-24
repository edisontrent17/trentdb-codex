package dev.trentdb.ast;

public record UnaryExpression(UnaryOperator operator, Expression expression) implements Expression {
}
