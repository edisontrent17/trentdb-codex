package dev.trentdb.ast;

public record InSubqueryExpression(Expression input, SelectStatement subquery, boolean negated) implements Expression {
}
