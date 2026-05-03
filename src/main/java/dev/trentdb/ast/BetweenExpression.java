package dev.trentdb.ast;

public record BetweenExpression(Expression input, Expression lower, Expression upper) implements Expression {
}
