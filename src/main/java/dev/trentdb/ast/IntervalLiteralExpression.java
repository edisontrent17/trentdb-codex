package dev.trentdb.ast;

public record IntervalLiteralExpression(long amount, IntervalUnit unit) implements Expression {
}
