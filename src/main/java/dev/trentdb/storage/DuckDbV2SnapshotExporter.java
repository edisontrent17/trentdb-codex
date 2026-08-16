package dev.trentdb.storage;

import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit, read-only bridge from TrentDB's in-memory committed snapshot to the bounded V2
 * checkpoint writer. It is intentionally separate from WAL and recovery: publishing this file
 * never changes the live catalog, storage manager, transaction state, or WAL.
 */
public final class DuckDbV2SnapshotExporter {
    private static final String CATALOG_NAME = "memory";
    private static final byte[] DATABASE_IDENTIFIER = new byte[16];

    private DuckDbV2SnapshotExporter() {
    }

    public static DuckDbV2CheckpointPublisher.Publication export(
            Path target, Catalog catalog, StorageManager storageManager, TransactionManager transactionManager
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(storageManager, "storageManager");
        Objects.requireNonNull(transactionManager, "transactionManager");

        Transaction snapshot = transactionManager.startReadTransaction();
        try {
            Catalog.CommittedSnapshot catalogSnapshot = catalog.committedSnapshot(snapshot);
            if (!catalogSnapshot.indexes().isEmpty()) {
                throw new StorageException("DuckDB V2 export does not support catalog indexes");
            }
            DuckDbV2CheckpointPublisher.Checkpoint checkpoint = checkpoint(catalogSnapshot, storageManager, snapshot);
            return publishAtomically(target, checkpoint);
        } finally {
            if (snapshot.state() == dev.trentdb.transaction.TransactionState.ACTIVE) {
                transactionManager.rollback(snapshot);
            }
        }
    }

    private static DuckDbV2CheckpointPublisher.Checkpoint checkpoint(
            Catalog.CommittedSnapshot snapshot, StorageManager storageManager, Transaction transaction
    ) {
        List<DuckDbSchemaCreateInfo> schemas = snapshot.schemas().stream()
                .map(schema -> new DuckDbSchemaCreateInfo(List.of(CATALOG_NAME, schema.name()), false, false,
                        DuckDbSchemaCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", ""))
                .toList();
        List<DuckDbV2CheckpointPublisher.PrimitiveTable> tables = new ArrayList<>();
        for (TableCatalogEntry table : snapshot.tables()) {
            List<List<Object>> rows = storageManager.exportCommittedRows(transaction, table);
            tables.add(new DuckDbV2CheckpointPublisher.PrimitiveTable(createInfo(table), columns(table, rows)));
        }
        return new DuckDbV2CheckpointPublisher.Checkpoint(schemas, List.of(), tables);
    }

    private static DuckDbTableCreateInfo createInfo(TableCatalogEntry table) {
        List<DuckDbTableCreateInfo.Column> columns = table.columns().stream()
                .map(DuckDbV2SnapshotExporter::column)
                .toList();
        return new DuckDbTableCreateInfo(List.of(CATALOG_NAME, table.schema().name(), table.name()), false, false,
                DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", table.name(), columns,
                DuckDbTableCreateInfo.Boundary.TABLE_METADATA_FIELD_101_UNSUPPORTED);
    }

    private static DuckDbTableCreateInfo.Column column(ColumnCatalogEntry column) {
        return new DuckDbTableCreateInfo.Column(column.name(), scalarType(column),
                DuckDbTableCreateInfo.Category.STANDARD, DuckDbTableCreateInfo.Compression.UNCOMPRESSED);
    }

    private static DuckDbTableCreateInfo.ScalarLogicalType scalarType(ColumnCatalogEntry column) {
        return switch (column.logicalType().id()) {
        case BOOLEAN -> DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN;
        case INTEGER -> DuckDbTableCreateInfo.ScalarLogicalType.INTEGER;
        case BIGINT -> DuckDbTableCreateInfo.ScalarLogicalType.BIGINT;
        default -> throw new StorageException("DuckDB V2 export does not support column type "
                + column.logicalType().id() + " for " + column.name());
        };
    }

    private static List<List<Long>> columns(TableCatalogEntry table, List<List<Object>> rows) {
        List<List<Long>> columns = new ArrayList<>(table.columns().size());
        for (int columnIndex = 0; columnIndex < table.columns().size(); columnIndex++) {
            ColumnCatalogEntry column = table.columns().get(columnIndex);
            List<Long> values = new ArrayList<>(rows.size());
            for (List<Object> row : rows) {
                if (row.size() != table.columns().size()) {
                    throw new StorageException("DuckDB V2 export found a malformed committed row in table " + table.name());
                }
                values.add(primitiveValue(column, row.get(columnIndex)));
            }
            columns.add(java.util.Collections.unmodifiableList(new ArrayList<>(values)));
        }
        return List.copyOf(columns);
    }

    private static Long primitiveValue(ColumnCatalogEntry column, Object value) {
        if (value == null) {
            return null;
        }
        return switch (column.logicalType().id()) {
        case BOOLEAN -> {
            if (!(value instanceof Boolean bool)) {
                throw valueType(column, value);
            }
            yield bool ? 1L : 0L;
        }
        case INTEGER -> checkedNumber(column, value, Integer.MIN_VALUE, Integer.MAX_VALUE);
        case BIGINT -> checkedNumber(column, value, Long.MIN_VALUE, Long.MAX_VALUE);
        default -> throw new StorageException("DuckDB V2 export does not support column type "
                + column.logicalType().id() + " for " + column.name());
        };
    }

    private static long checkedNumber(ColumnCatalogEntry column, Object value, long minimum, long maximum) {
        if (!(value instanceof Number number)) {
            throw valueType(column, value);
        }
        long result = number.longValue();
        if ((number instanceof Float || number instanceof Double) && number.doubleValue() != result) {
            throw valueType(column, value);
        }
        if (result < minimum || result > maximum) {
            throw valueType(column, value);
        }
        return result;
    }

    private static StorageException valueType(ColumnCatalogEntry column, Object value) {
        return new StorageException("DuckDB V2 export found unsupported committed value "
                + value.getClass().getSimpleName() + " for " + column.name() + " (" + column.logicalType().id() + ")");
    }

    private static DuckDbV2CheckpointPublisher.Publication publishAtomically(
            Path target, DuckDbV2CheckpointPublisher.Checkpoint checkpoint
    ) {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new StorageException("DuckDB V2 export target parent does not exist: " + target);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, "." + absolute.getFileName() + ".", ".tmp");
            Files.deleteIfExists(temporary);
            DuckDbV2CheckpointPublisher.Publication publication =
                    DuckDbV2CheckpointPublisher.create(temporary, DATABASE_IDENTIFIER, checkpoint);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new StorageException("DuckDB V2 export requires an atomic target move", exception);
            }
            return new DuckDbV2CheckpointPublisher.Publication(absolute, publication.root(), publication.iteration(),
                    publication.blockCount());
        } catch (IOException exception) {
            throw new StorageException("Unable to atomically publish DuckDB V2 export: " + absolute, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The published target is already complete; a sibling temporary is best-effort cleanup only.
                }
            }
        }
    }
}
