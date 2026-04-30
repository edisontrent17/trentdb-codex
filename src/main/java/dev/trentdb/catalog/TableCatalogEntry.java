package dev.trentdb.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TableCatalogEntry extends CatalogEntry {
    private final SchemaCatalogEntry schema;
    private final List<ColumnCatalogEntry> columns;
    private final Map<String, ColumnCatalogEntry> columnsByName;

    public TableCatalogEntry(SchemaCatalogEntry schema, String name, List<ColumnCatalogEntry> columns) {
        super(CatalogEntryType.TABLE, name);
        if (schema == null) {
            throw new IllegalArgumentException("Table schema must not be null");
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Table must contain at least one column");
        }
        this.schema = schema;
        this.columns = List.copyOf(columns);
        this.columnsByName = indexColumns(columns);
    }

    public SchemaCatalogEntry schema() {
        return schema;
    }

    public List<ColumnCatalogEntry> columns() {
        return columns;
    }

    public ColumnCatalogEntry lookupColumn(String columnName) {
        var column = columnsByName.get(columnName);
        if (column == null) {
            throw new CatalogException("Column not found: " + columnName);
        }
        return column;
    }

    private Map<String, ColumnCatalogEntry> indexColumns(List<ColumnCatalogEntry> columns) {
        var result = new LinkedHashMap<String, ColumnCatalogEntry>();
        for (var column : columns) {
            var previous = result.putIfAbsent(column.name(), column);
            if (previous != null) {
                throw new CatalogException("Column already exists: " + column.name());
            }
        }
        return Map.copyOf(result);
    }
}
