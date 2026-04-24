package dev.trentdb.ast;

public record ColumnReferenceExpression(QualifiedName name) implements Expression {
}
