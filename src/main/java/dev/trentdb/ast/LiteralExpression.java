package dev.trentdb.ast;

public record LiteralExpression(LiteralKind kind, Object value) implements Expression {
}
