package dev.trentdb.planner;

public sealed interface BoundStatement permits BoundCreateTableStatement, BoundDropTableStatement, BoundCreateIndexStatement, BoundDropIndexStatement, BoundInsertStatement, BoundDeleteStatement, BoundUpdateStatement, BoundExplainStatement, BoundSelectStatement {
}
