package dev.duckdbjava.ast;

public record ColumnReferenceExpression(QualifiedName name) implements Expression {
}
