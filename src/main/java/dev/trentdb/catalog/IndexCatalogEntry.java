package dev.trentdb.catalog;

import dev.trentdb.ast.IndexKey;
import dev.trentdb.ast.SortDirection;

import java.util.List;
import java.util.Objects;

/** Versioned non-unique index definition; physical access-path construction is not implemented. */
public final class IndexCatalogEntry extends CatalogEntry {
    private final SchemaCatalogEntry schema;
    private final TableCatalogEntry table;
    private final List<IndexKey> keys;

    public IndexCatalogEntry(SchemaCatalogEntry schema, String name, TableCatalogEntry table, List<IndexKey> keys) {
        super(CatalogEntryType.INDEX, name);
        this.schema = Objects.requireNonNull(schema, "schema");
        this.table = Objects.requireNonNull(table, "table");
        this.keys = List.copyOf(keys);
        if (this.keys.isEmpty()) throw new IllegalArgumentException("Index must contain at least one key");
    }

    /** Compatibility constructor for existing metadata-only callers. */
    public IndexCatalogEntry(String name, TableCatalogEntry table, List<ColumnCatalogEntry> columns) {
        this(table.schema(), name, table, columns.stream().map(column -> new IndexKey(column.name(), SortDirection.ASC)).toList());
    }

    public SchemaCatalogEntry schema() { return schema; }
    public TableCatalogEntry table() { return table; }
    public List<IndexKey> keys() { return keys; }
    public List<ColumnCatalogEntry> columns() { return keys.stream().map(key -> table.lookupColumn(key.columnName())).toList(); }
}
