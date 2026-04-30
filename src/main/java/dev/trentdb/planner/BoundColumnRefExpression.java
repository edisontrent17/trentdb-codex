package dev.trentdb.planner;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.types.LogicalType;

public record BoundColumnRefExpression(ColumnCatalogEntry column) implements BoundExpression {
    public String name() {
        return column.name();
    }

    public LogicalType logicalType() {
        return column.logicalType();
    }

    public int ordinal() {
        return column.ordinal();
    }
}
