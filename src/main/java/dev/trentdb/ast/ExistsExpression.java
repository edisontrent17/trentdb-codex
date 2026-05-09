package dev.trentdb.ast;

public record ExistsExpression(SelectStatement select) implements Expression {
}
