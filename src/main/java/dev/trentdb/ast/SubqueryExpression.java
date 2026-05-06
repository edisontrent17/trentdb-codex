package dev.trentdb.ast;

public record SubqueryExpression(SelectStatement select) implements Expression {
}
