package dev.trentdb.catalog;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class Catalog {
    public static final String DEFAULT_SCHEMA = "public";

    private final CatalogSet<SchemaCatalogEntry> schemas = new CatalogSet<>(CatalogEntryType.SCHEMA);

    public Catalog() {
        createInitialSchema(DEFAULT_SCHEMA);
    }

    public SchemaCatalogEntry createSchema(Transaction transaction, String schemaName) {
        requireTransaction(transaction);
        var schema = new SchemaCatalogEntry(schemaName);
        schemas.createEntry(schema);
        return schema;
    }

    public SchemaCatalogEntry lookupSchema(Transaction transaction, String schemaName) {
        requireTransaction(transaction);
        return schemas.getEntry(schemaName);
    }

    public TableCatalogEntry createTable(Transaction transaction, CreateTableStatement statement) {
        return createTable(transaction, statement.name(), statement.columns());
    }

    public TableCatalogEntry createTable(Transaction transaction, QualifiedName tableName, List<ColumnDefinition> columns) {
        requireTransaction(transaction);
        var relationName = resolveRelationName(tableName);
        var schema = lookupSchema(transaction, relationName.schemaName());
        return schema.createTable(transaction, relationName.tableName(), catalogColumns(columns));
    }

    public TableCatalogEntry lookupTable(Transaction transaction, QualifiedName tableName) {
        requireTransaction(transaction);
        var relationName = resolveRelationName(tableName);
        return lookupSchema(transaction, relationName.schemaName()).lookupTable(transaction, relationName.tableName());
    }

    private List<ColumnCatalogEntry> catalogColumns(List<ColumnDefinition> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new CatalogException("Table must contain at least one column");
        }
        var result = new ArrayList<ColumnCatalogEntry>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            var column = columns.get(index);
            result.add(new ColumnCatalogEntry(column.name(), LogicalType.from(column.type()), index));
        }
        return result;
    }

    private RelationName resolveRelationName(QualifiedName name) {
        if (name.parts().size() == 1) {
            return new RelationName(DEFAULT_SCHEMA, name.last());
        }
        if (name.parts().size() == 2) {
            return new RelationName(name.parts().get(0), name.parts().get(1));
        }
        throw new CatalogException("Only schema-qualified table names are supported: " + String.join(".", name.parts()));
    }

    private record RelationName(String schemaName, String tableName) {
    }

    private void createInitialSchema(String schemaName) {
        schemas.createEntry(new SchemaCatalogEntry(schemaName));
    }

    private void requireTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Catalog operation requires a transaction");
        }
    }
}
