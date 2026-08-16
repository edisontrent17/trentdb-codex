package dev.trentdb.ast;

public sealed interface Statement permits CreateTableStatement, DropTableStatement, CreateIndexStatement, DropIndexStatement, InsertStatement, DeleteStatement, UpdateStatement, BeginTransactionStatement, CommitStatement, RollbackStatement, SelectStatement, ExplainStatement {
}
