package dev.trentdb.catalog;

import dev.trentdb.transaction.Transaction;

import java.util.List;

public final class SchemaCatalogEntry extends CatalogEntry {
    private final CatalogSet<TableCatalogEntry> tables = new CatalogSet<>(CatalogEntryType.TABLE);

    public SchemaCatalogEntry(String name) {
        super(CatalogEntryType.SCHEMA, name);
    }

    public TableCatalogEntry createTable(Transaction transaction, String tableName, List<ColumnCatalogEntry> columns) {
        requireTransaction(transaction);
        var table = new TableCatalogEntry(this, tableName, columns);
        tables.createEntry(table);
        return table;
    }

    public TableCatalogEntry lookupTable(Transaction transaction, String tableName) {
        requireTransaction(transaction);
        return tables.getEntry(tableName);
    }

    private void requireTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Schema catalog operation requires a transaction");
        }
    }
}
