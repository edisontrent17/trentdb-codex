package dev.duckdbjava.ast;

import java.util.List;

public record CreateTableStatement(QualifiedName name, List<ColumnDefinition> columns) implements Statement {
}
