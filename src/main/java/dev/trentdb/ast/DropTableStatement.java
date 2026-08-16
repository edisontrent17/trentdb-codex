package dev.trentdb.ast;

/** DROP TABLE without IF EXISTS, CASCADE, or other modifiers. */
public record DropTableStatement(QualifiedName name) implements Statement {
}
