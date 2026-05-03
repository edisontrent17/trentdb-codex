package dev.trentdb.planner;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.types.LogicalType;

public record BoundColumnRefExpression(ColumnCatalogEntry column, int ordinal) implements BoundExpression {
    public BoundColumnRefExpression(ColumnCatalogEntry column) {
        this(column, column.ordinal());
    }

    public String name() {
        return column.name();
    }

    public LogicalType logicalType() {
        return column.logicalType();
    }

}
