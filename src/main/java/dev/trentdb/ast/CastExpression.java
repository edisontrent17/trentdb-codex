package dev.trentdb.ast;

public record CastExpression(Expression child, TypeName targetType) implements Expression {
}
