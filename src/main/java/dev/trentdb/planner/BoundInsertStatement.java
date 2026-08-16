package dev.trentdb.planner;

import dev.trentdb.catalog.TableCatalogEntry;
import java.util.ArrayList;
import java.util.List;

/** Bound INSERT VALUES statement with a resolved mapping and immutable row order. */
public record BoundInsertStatement(
        TableCatalogEntry table,
        List<Integer> targetOrdinals,
        List<BoundLiteralExpression> values,
        List<List<BoundLiteralExpression>> rows
) implements BoundStatement {
    public BoundInsertStatement {
        targetOrdinals = List.copyOf(targetOrdinals);
        values = List.copyOf(values);
        rows = rows.stream().map(List::copyOf).toList();
        if (rows.isEmpty()) throw new IllegalArgumentException("INSERT requires at least one VALUES row");
        if (!values.equals(rows.getFirst())) throw new IllegalArgumentException("INSERT first-row compatibility values must match rows");
    }
    public BoundInsertStatement(TableCatalogEntry table, List<Integer> targetOrdinals, List<BoundLiteralExpression> values) {
        this(table, targetOrdinals, values, List.of(new ArrayList<>(values)));
    }
}
