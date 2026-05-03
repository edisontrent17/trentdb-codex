package dev.trentdb.ast;

import java.util.List;

public record InExpression(Expression input, List<Expression> candidates, boolean negated) implements Expression {
    public InExpression {
        candidates = List.copyOf(candidates);
    }
}
