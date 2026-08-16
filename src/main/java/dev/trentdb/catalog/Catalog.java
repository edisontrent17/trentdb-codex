package dev.trentdb.catalog;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.CreateIndexStatement;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.IndexKey;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionParticipant;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transactional catalog metadata with snapshot-visible entry histories. */
public final class Catalog implements TransactionParticipant {
    public static final String DEFAULT_SCHEMA = "public";

    private VersionedCatalogSet<SchemaCatalogEntry> schemas = new VersionedCatalogSet<>(CatalogEntryType.SCHEMA);
    private VersionedCatalogSet<TableCatalogEntry> tables = new VersionedCatalogSet<>(CatalogEntryType.TABLE);
    private VersionedCatalogSet<IndexCatalogEntry> indexes = new VersionedCatalogSet<>(CatalogEntryType.INDEX);
    private final Map<Transaction, CatalogTransactionState> pendingTransactions = new IdentityHashMap<>();

    public Catalog() { schemas.installInitial(new SchemaCatalogEntry(DEFAULT_SCHEMA)); }

    public synchronized SchemaCatalogEntry createSchema(Transaction transaction, String schemaName) {
        requireTransaction(transaction);
        if (visibleSchema(transaction, schemaName) != null) throw new CatalogException("Schema already exists: " + schemaName);
        var schema = new SchemaCatalogEntry(schemaName);
        stateForWrite(transaction).stageSchema(schemaName, committedSchema(transaction, schemaName), schema);
        return schema;
    }

    public synchronized SchemaCatalogEntry lookupSchema(Transaction transaction, String schemaName) {
        requireTransaction(transaction);
        var schema = visibleSchema(transaction, schemaName);
        if (schema == null) throw new CatalogException("Schema not found: " + schemaName);
        return schema;
    }

    public TableCatalogEntry createTable(Transaction transaction, CreateTableStatement statement) {
        return createTable(transaction, statement.name(), statement.columns());
    }

    public synchronized TableCatalogEntry createTable(Transaction transaction, QualifiedName tableName, List<ColumnDefinition> columns) {
        requireTransaction(transaction);
        var name = resolveRelationName(tableName);
        var schema = lookupSchema(transaction, name.schemaName());
        var key = key(name);
        if (visibleTable(transaction, key) != null) throw new CatalogException("Table already exists: " + name.objectName());
        var table = new TableCatalogEntry(schema, name.objectName(), catalogColumns(columns));
        stateForWrite(transaction).stageTable(key, committedTable(transaction, key), table);
        return table;
    }

    public synchronized TableCatalogEntry lookupTable(Transaction transaction, QualifiedName tableName) {
        requireTransaction(transaction);
        var name = resolveRelationName(tableName);
        lookupSchema(transaction, name.schemaName());
        var table = visibleTable(transaction, key(name));
        if (table == null) throw new CatalogException("Table not found: " + name.objectName());
        return table;
    }

    public synchronized void dropTable(Transaction transaction, QualifiedName tableName) {
        var table = lookupTable(transaction, tableName);
        var name = new RelationName(table.schema().name(), table.name());
        stateForWrite(transaction).stageTable(key(name), committedTable(transaction, key(name)), null);
        stageIndexesForDroppedTable(transaction, table);
    }

    public IndexCatalogEntry createIndex(Transaction transaction, CreateIndexStatement statement) {
        return createIndex(transaction, statement.name(), statement.tableName(), statement.keys());
    }

    public synchronized IndexCatalogEntry createIndex(
            Transaction transaction, QualifiedName indexName, QualifiedName tableName, List<IndexKey> keys
    ) {
        requireTransaction(transaction);
        var name = resolveRelationName(indexName);
        var schema = lookupSchema(transaction, name.schemaName());
        var indexKey = key(name);
        if (visibleIndex(transaction, indexKey) != null) throw new CatalogException("Index already exists: " + name.objectName());
        var table = lookupTable(transaction, tableName);
        var seen = new java.util.HashSet<String>();
        if (keys == null || keys.isEmpty()) throw new CatalogException("Index must contain at least one key");
        for (var indexKeyDefinition : keys) {
            if (!seen.add(indexKeyDefinition.columnName())) {
                throw new CatalogException("Index key specified more than once: " + indexKeyDefinition.columnName());
            }
            table.lookupColumn(indexKeyDefinition.columnName());
        }
        var index = new IndexCatalogEntry(schema, name.objectName(), table, keys);
        stateForWrite(transaction).stageIndex(indexKey, committedIndex(transaction, indexKey), index);
        return index;
    }

    public synchronized IndexCatalogEntry lookupIndex(Transaction transaction, QualifiedName indexName) {
        requireTransaction(transaction);
        var name = resolveRelationName(indexName);
        lookupSchema(transaction, name.schemaName());
        var index = visibleIndex(transaction, key(name));
        if (index == null) throw new CatalogException("Index not found: " + name.objectName());
        return index;
    }

    /**
     * Returns the fully committed catalog view at a read transaction's snapshot.
     * Export callers deliberately cannot include another transaction's staged metadata.
     */
    public synchronized CommittedSnapshot committedSnapshot(Transaction transaction) {
        requireTransaction(transaction);
        if (!pendingTransactions.isEmpty()) {
            throw new CatalogException("DuckDB V2 export requires no uncommitted catalog changes");
        }
        long version = transaction.snapshot().catalogVersion();
        return new CommittedSnapshot(schemas.visibleEntries(version), tables.visibleEntries(version),
                indexes.visibleEntries(version));
    }

    public record CommittedSnapshot(List<SchemaCatalogEntry> schemas, List<TableCatalogEntry> tables,
                                    List<IndexCatalogEntry> indexes) { }

    public synchronized void dropIndex(Transaction transaction, QualifiedName indexName) {
        lookupIndex(transaction, indexName);
        var name = resolveRelationName(indexName);
        var indexKey = key(name);
        stateForWrite(transaction).stageIndex(indexKey, committedIndex(transaction, indexKey), null);
    }

    @Override
    public synchronized void prepareCommit(Transaction transaction, long commitVersion) {
        var state = pendingTransactions.get(transaction);
        if (state != null) state.prepare(this, commitVersion);
    }

    @Override
    public synchronized void commit(Transaction transaction, long commitVersion) {
        var state = pendingTransactions.get(transaction);
        if (state != null) state.publish(this, commitVersion);
    }

    @Override
    public synchronized void completeCommit(Transaction transaction, long commitVersion) {
        var state = pendingTransactions.get(transaction);
        if (state == null) return;
        if (!state.isPublishedAt(commitVersion)) throw new IllegalStateException("Catalog transaction was not published for commit completion");
        pendingTransactions.remove(transaction);
    }

    @Override
    public synchronized void rollback(Transaction transaction) {
        var state = pendingTransactions.remove(transaction);
        if (state != null) state.rollbackPublishedChanges(this);
    }

    private void stageIndexesForDroppedTable(Transaction transaction, TableCatalogEntry table) {
        var visible = new LinkedHashMap<String, IndexCatalogEntry>();
        for (var index : indexes.visibleEntries(transaction.snapshot().catalogVersion())) {
            visible.put(key(new RelationName(index.schema().name(), index.name())), index);
        }
        var state = pendingTransactions.get(transaction);
        if (state != null) {
            for (var change : state.indexChanges.entrySet()) {
                if (change.getValue().replacement == null) visible.remove(change.getKey());
                else visible.put(change.getKey(), change.getValue().replacement);
            }
        }
        for (var entry : visible.entrySet()) {
            if (entry.getValue().table() == table) {
                stateForWrite(transaction).stageIndex(entry.getKey(), committedIndex(transaction, entry.getKey()), null);
            }
        }
    }

    private List<ColumnCatalogEntry> catalogColumns(List<ColumnDefinition> columns) {
        if (columns == null || columns.isEmpty()) throw new CatalogException("Table must contain at least one column");
        var result = new ArrayList<ColumnCatalogEntry>(columns.size());
        for (int ordinal = 0; ordinal < columns.size(); ordinal++) {
            var column = columns.get(ordinal);
            result.add(new ColumnCatalogEntry(column.name(), LogicalType.from(column.type()), ordinal));
        }
        return result;
    }

    private RelationName resolveRelationName(QualifiedName name) {
        if (name.parts().size() == 1) return new RelationName(DEFAULT_SCHEMA, name.last());
        if (name.parts().size() == 2) return new RelationName(name.parts().get(0), name.parts().get(1));
        throw new CatalogException("Only schema-qualified names are supported: " + String.join(".", name.parts()));
    }

    private void requireTransaction(Transaction transaction) {
        if (transaction == null) throw new IllegalArgumentException("Catalog operation requires a transaction");
        transaction.requireActive();
    }

    private CatalogTransactionState stateForWrite(Transaction transaction) {
        var state = pendingTransactions.get(transaction);
        if (state == null) {
            state = new CatalogTransactionState();
            pendingTransactions.put(transaction, state);
            transaction.enlist(this);
        }
        return state;
    }

    private SchemaCatalogEntry visibleSchema(Transaction transaction, String name) {
        var state = pendingTransactions.get(transaction);
        if (state != null && state.schemaChanges.containsKey(name)) return state.schemaChanges.get(name).replacement;
        return schemas.lookupOrNull(name, transaction.snapshot().catalogVersion());
    }

    private TableCatalogEntry visibleTable(Transaction transaction, String name) {
        var state = pendingTransactions.get(transaction);
        if (state != null && state.tableChanges.containsKey(name)) return state.tableChanges.get(name).replacement;
        return committedTable(transaction, name);
    }

    private IndexCatalogEntry visibleIndex(Transaction transaction, String name) {
        var state = pendingTransactions.get(transaction);
        if (state != null && state.indexChanges.containsKey(name)) return state.indexChanges.get(name).replacement;
        return committedIndex(transaction, name);
    }

    private SchemaCatalogEntry committedSchema(Transaction transaction, String name) {
        return schemas.lookupOrNull(name, transaction.snapshot().catalogVersion());
    }

    private TableCatalogEntry committedTable(Transaction transaction, String name) {
        return tables.lookupOrNull(name, transaction.snapshot().catalogVersion());
    }

    private IndexCatalogEntry committedIndex(Transaction transaction, String name) {
        return indexes.lookupOrNull(name, transaction.snapshot().catalogVersion());
    }

    private SchemaCatalogEntry currentSchema(String name) { return schemas.lookupOrNull(name, Long.MAX_VALUE); }
    private TableCatalogEntry currentTable(String name) { return tables.lookupOrNull(name, Long.MAX_VALUE); }
    private IndexCatalogEntry currentIndex(String name) { return indexes.lookupOrNull(name, Long.MAX_VALUE); }
    private static String key(RelationName name) { return name.schemaName() + "\u0000" + name.objectName(); }
    private record RelationName(String schemaName, String objectName) { }

    private static final class CatalogTransactionState {
        private final Map<String, CatalogChange<SchemaCatalogEntry>> schemaChanges = new LinkedHashMap<>();
        private final Map<String, CatalogChange<TableCatalogEntry>> tableChanges = new LinkedHashMap<>();
        private final Map<String, CatalogChange<IndexCatalogEntry>> indexChanges = new LinkedHashMap<>();
        private Long preparedCommitVersion;
        private VersionedCatalogSet<SchemaCatalogEntry> baseSchemas;
        private VersionedCatalogSet<TableCatalogEntry> baseTables;
        private VersionedCatalogSet<IndexCatalogEntry> baseIndexes;
        private VersionedCatalogSet<SchemaCatalogEntry> preparedSchemas;
        private VersionedCatalogSet<TableCatalogEntry> preparedTables;
        private VersionedCatalogSet<IndexCatalogEntry> preparedIndexes;
        private boolean published;

        private void stageSchema(String key, SchemaCatalogEntry expected, SchemaCatalogEntry replacement) { stage(schemaChanges, key, expected, replacement); }
        private void stageTable(String key, TableCatalogEntry expected, TableCatalogEntry replacement) { stage(tableChanges, key, expected, replacement); }
        private void stageIndex(String key, IndexCatalogEntry expected, IndexCatalogEntry replacement) { stage(indexChanges, key, expected, replacement); }

        private <T extends CatalogEntry> void stage(Map<String, CatalogChange<T>> changes, String key, T expected, T replacement) {
            changes.compute(key, (ignored, existing) -> existing == null ? new CatalogChange<>(expected, replacement) : existing.withReplacement(replacement));
        }

        private void prepare(Catalog catalog, long commitVersion) {
            if (preparedCommitVersion != null) throw new IllegalStateException("Catalog transaction has already been prepared");
            validateChanges(schemaChanges, catalog::currentSchema);
            validateChanges(tableChanges, catalog::currentTable);
            validateChanges(indexChanges, catalog::currentIndex);
            validateTableSchemas(catalog);
            baseSchemas = catalog.schemas;
            baseTables = catalog.tables;
            baseIndexes = catalog.indexes;
            preparedSchemas = catalog.schemas.copy();
            preparedTables = catalog.tables.copy();
            preparedIndexes = catalog.indexes.copy();
            applyChanges(schemaChanges, preparedSchemas, commitVersion);
            applyChanges(tableChanges, preparedTables, commitVersion);
            applyChanges(indexChanges, preparedIndexes, commitVersion);
            preparedCommitVersion = commitVersion;
        }

        private void validateTableSchemas(Catalog catalog) {
            for (var tableChange : tableChanges.values()) {
                var table = tableChange.replacement;
                if (table != null && catalog.currentSchema(table.schema().name()) == null && replacementSchema(table.schema().name()) == null) {
                    throw new CatalogException("Schema not found: " + table.schema().name());
                }
            }
        }

        private SchemaCatalogEntry replacementSchema(String name) {
            var change = schemaChanges.get(name);
            return change == null ? null : change.replacement;
        }

        private <T extends CatalogEntry> void validateChanges(Map<String, CatalogChange<T>> changes, java.util.function.Function<String, T> currentLookup) {
            for (var change : changes.entrySet()) {
                if (!change.getValue().isNoOp() && currentLookup.apply(change.getKey()) != change.getValue().expected) {
                    throw new CatalogException("Catalog entry changed concurrently: " + change.getKey());
                }
            }
        }

        private void publish(Catalog catalog, long commitVersion) {
            if (preparedCommitVersion == null || preparedCommitVersion != commitVersion) throw new IllegalStateException("Catalog transaction was not prepared for this commit version");
            if (catalog.schemas != baseSchemas || catalog.tables != baseTables || catalog.indexes != baseIndexes) {
                throw new CatalogException("Catalog changed while the transaction was being committed");
            }
            catalog.schemas = preparedSchemas;
            catalog.tables = preparedTables;
            catalog.indexes = preparedIndexes;
            published = true;
        }

        private boolean isPublishedAt(long commitVersion) { return published && preparedCommitVersion != null && preparedCommitVersion == commitVersion; }

        private void rollbackPublishedChanges(Catalog catalog) {
            if (!published) return;
            if (catalog.schemas != preparedSchemas || catalog.tables != preparedTables || catalog.indexes != preparedIndexes) {
                throw new IllegalStateException("Catalog changed after this transaction published its changes");
            }
            catalog.schemas = baseSchemas;
            catalog.tables = baseTables;
            catalog.indexes = baseIndexes;
            published = false;
        }

        private <T extends CatalogEntry> void applyChanges(Map<String, CatalogChange<T>> changes, VersionedCatalogSet<T> entries, long commitVersion) {
            for (var change : changes.entrySet()) if (!change.getValue().isNoOp()) {
                entries.apply(change.getKey(), change.getValue().expected, change.getValue().replacement, commitVersion);
            }
        }
    }

    private static final class CatalogChange<T extends CatalogEntry> {
        private final T expected;
        private final T replacement;
        private CatalogChange(T expected, T replacement) { this.expected = expected; this.replacement = replacement; }
        private CatalogChange<T> withReplacement(T nextReplacement) { return new CatalogChange<>(expected, nextReplacement); }
        private boolean isNoOp() { return expected == null && replacement == null; }
    }
}
