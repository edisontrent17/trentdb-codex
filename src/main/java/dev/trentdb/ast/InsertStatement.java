package dev.trentdb.ast;

import java.util.ArrayList;
import java.util.List;

/** Narrow INSERT VALUES statement. Rows are non-empty and retain SQL statement order. */
public record InsertStatement(
        QualifiedName tableName,
        List<String> columns,
        List<Expression> values,
        List<List<Expression>> rows
) implements Statement {
    public InsertStatement {
        columns = List.copyOf(columns);
        values = List.copyOf(values);
        rows = rows.stream().map(List::copyOf).toList();
        if (rows.isEmpty()) throw new IllegalArgumentException("INSERT requires at least one VALUES row");
        if (!values.equals(rows.getFirst())) throw new IllegalArgumentException("INSERT first-row compatibility values must match rows");
    }
    /** Compatibility constructor for existing single-row callers. */
    public InsertStatement(QualifiedName tableName, List<String> columns, List<Expression> values) {
        this(tableName, columns, values, List.of(new ArrayList<>(values)));
    }
}
