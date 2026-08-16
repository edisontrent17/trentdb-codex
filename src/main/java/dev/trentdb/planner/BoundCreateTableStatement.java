package dev.trentdb.planner;

import dev.trentdb.ast.CreateTableStatement;

/** Bound DDL retains syntax only; catalog mutation occurs at the write boundary. */
public record BoundCreateTableStatement(CreateTableStatement statement) implements BoundStatement {
}
