package dev.trentdb.ast;

/** Narrow single-column UPDATE statement; multi-assignment is intentionally unsupported. */
public record UpdateStatement(QualifiedName tableName, String columnName, Expression value, Expression where) implements Statement {
}
