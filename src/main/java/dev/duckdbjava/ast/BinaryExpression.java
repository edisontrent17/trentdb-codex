package dev.duckdbjava.ast;

public record BinaryExpression(Expression left, BinaryOperator operator, Expression right) implements Expression {
}
