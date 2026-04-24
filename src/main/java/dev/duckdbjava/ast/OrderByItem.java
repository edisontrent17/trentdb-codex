package dev.duckdbjava.ast;

public record OrderByItem(Expression expression, SortDirection direction) {
}
