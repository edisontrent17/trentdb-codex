package dev.trentdb.planner;

public sealed interface BoundStatement permits BoundExplainStatement, BoundSelectStatement {
}
