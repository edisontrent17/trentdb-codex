package dev.trentdb.ast;

import java.util.List;

public record InsertStatement(QualifiedName tableName, List<String> columns, List<Expression> values) implements Statement {
}
