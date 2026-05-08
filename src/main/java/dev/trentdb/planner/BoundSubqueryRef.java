package dev.trentdb.planner;

import dev.trentdb.catalog.ColumnCatalogEntry;

import java.util.List;

public record BoundSubqueryRef(
        BoundSelectStatement subquery,
        String relationName,
        List<ColumnCatalogEntry> columns
) implements BoundFrom {
    public BoundSubqueryRef {
        columns = List.copyOf(columns);
    }
}
