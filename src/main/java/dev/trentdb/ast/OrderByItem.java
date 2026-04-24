package dev.trentdb.ast;

public record OrderByItem(Expression expression, SortDirection direction) {
}
