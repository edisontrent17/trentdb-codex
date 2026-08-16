package dev.trentdb.ast;

import java.util.List;

public record CaseExpression(Expression baseExpression, List<WhenClause> branches, Expression elseExpression) implements Expression {
    public CaseExpression {
        branches = List.copyOf(branches);
    }

    public CaseExpression(List<WhenClause> branches, Expression elseExpression) {
        this(null, branches, elseExpression);
    }

    public record WhenClause(Expression condition, Expression result) {
    }
}
