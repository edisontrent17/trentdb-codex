package dev.trentdb.ast;

public record BetweenExpression(Expression input, Expression lower, Expression upper, boolean negated) implements Expression {
    public BetweenExpression(Expression input, Expression lower, Expression upper) {
        this(input, lower, upper, false);
    }
}
