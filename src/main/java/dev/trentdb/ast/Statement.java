package dev.trentdb.ast;

public sealed interface Statement permits CreateTableStatement, InsertStatement, SelectStatement, ExplainStatement {
}
