package dev.trentdb.ast;

/** Commits the active connection-scoped transaction. */
public record CommitStatement() implements Statement { }
