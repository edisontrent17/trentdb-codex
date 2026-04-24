package dev.duckdbjava.ast;

public record LiteralExpression(LiteralKind kind, Object value) implements Expression {
}
