package dev.duckdbjava.ast;

public sealed interface Statement permits CreateTableStatement, InsertStatement, SelectStatement, ExplainStatement {
}
