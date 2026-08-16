package dev.trentdb.planner;

import dev.trentdb.ast.DropTableStatement;

/** Bound DDL retains syntax only; catalog mutation occurs at the write boundary. */
public record BoundDropTableStatement(DropTableStatement statement) implements BoundStatement {
}
