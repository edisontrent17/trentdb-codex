package dev.trentdb.catalog;

import dev.trentdb.types.LogicalType;

public final class ColumnCatalogEntry extends CatalogEntry {
    private final LogicalType logicalType;
    private final int ordinal;

    public ColumnCatalogEntry(String name, LogicalType logicalType, int ordinal) {
        super(CatalogEntryType.COLUMN, name);
        if (logicalType == null) {
            throw new IllegalArgumentException("Column logical type must not be null");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("Column ordinal must not be negative");
        }
        this.logicalType = logicalType;
        this.ordinal = ordinal;
    }

    public LogicalType logicalType() {
        return logicalType;
    }

    public int ordinal() {
        return ordinal;
    }
}
