package dev.trentdb.ast;

/** Rolls back the active connection-scoped transaction. */
public record RollbackStatement() implements Statement { }
