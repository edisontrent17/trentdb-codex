package dev.trentdb.ast;

public record BinaryExpression(Expression left, BinaryOperator operator, Expression right) implements Expression {
}
