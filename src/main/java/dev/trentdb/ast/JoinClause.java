package dev.trentdb.ast;

public record JoinClause(JoinType type, TableReference right, Expression condition) {
}
