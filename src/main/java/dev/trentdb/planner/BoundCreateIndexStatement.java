package dev.trentdb.planner;

import dev.trentdb.ast.CreateIndexStatement;

/** Bound index DDL remains metadata-only until physical access paths are introduced. */
public record BoundCreateIndexStatement(CreateIndexStatement statement) implements BoundStatement { }
