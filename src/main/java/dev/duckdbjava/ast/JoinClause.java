package dev.duckdbjava.ast;

public record JoinClause(JoinType type, TableReference right, Expression condition) {
}
