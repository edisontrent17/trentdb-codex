package dev.trentdb.ast;

import java.util.List;

public record CaseExpression(List<WhenClause> branches, Expression elseExpression) implements Expression {
    public CaseExpression {
        branches = List.copyOf(branches);
    }

    public record WhenClause(Expression condition, Expression result) {
    }
}
