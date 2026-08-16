package dev.trentdb.storage;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.execution.ddl.DdlWalPayload;
import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.Transaction;
import dev.trentdb.transaction.TransactionManager;
import dev.trentdb.transaction.TransactionState;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Strict inverse of the bounded V2 checkpoint publisher. It decodes the source completely before
 * producing a fresh TWLF WAL, then atomically installs that WAL; the source database is read-only.
 */
public final class DuckDbV2SnapshotImporter {
    private static final String CATALOG_NAME = "memory";
    private static final String LIVE_SCHEMA = Catalog.DEFAULT_SCHEMA;

    private DuckDbV2SnapshotImporter() {
    }

    /** Validates {@code source} and atomically replaces {@code walTarget} with its durable live seed. */
    public static void importToWal(Path source, Path walTarget) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(walTarget, "walTarget");
        if (source.toAbsolutePath().normalize().equals(walTarget.toAbsolutePath().normalize())) {
            throw new StorageException("DuckDB V2 import source and WAL target must differ");
        }
        Snapshot snapshot = read(source);
        Path target = walTarget.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new StorageException("DuckDB V2 import WAL target parent does not exist: " + walTarget);
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
            seedWal(temporary, snapshot);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new StorageException("DuckDB V2 import requires an atomic WAL target move", exception);
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to atomically install DuckDB V2 import WAL: " + target, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A published target is complete; stale sibling cleanup is best effort.
                }
            }
        }
    }

    private static Snapshot read(Path source) {
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(source)) {
            if (manager.activeHeader().freeList() != StorageFormat.INVALID_BLOCK) {
                throw new StorageFormatException("DuckDB V2 import does not support a free-list root");
            }
            DuckDbCheckpointEnvelopeReader catalog = DuckDbCheckpointEnvelopeReader.openActiveCheckpoint(manager);
            long entries = catalog.beginCheckpoint();
            boolean publicSchema = false;
            List<ImportedTable> tables = new ArrayList<>();
            for (long index = 0; index < entries; index++) {
                DuckDbCheckpointEnvelopeReader.CatalogEntryEnvelope envelope = catalog.readNextEntryEnvelope();
                switch (envelope.type()) {
                case SCHEMA -> {
                    DuckDbSchemaCreateInfo schema = catalog.readSchemaCreateInfo();
                    if (!schema.qualifiedNamePath().equals(List.of(CATALOG_NAME, LIVE_SCHEMA)) || schema.temporary()
                            || schema.internal() || schema.onConflict() != DuckDbSchemaCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT
                            || !schema.sql().isEmpty() || !schema.extensionName().isEmpty()) {
                        throw new StorageFormatException("DuckDB V2 import supports only the default public schema");
                    }
                    if (publicSchema) throw new StorageFormatException("DuckDB V2 import contains duplicate public schema metadata");
                    publicSchema = true;
                }
                case TABLE -> tables.add(readTable(manager, catalog.readTableEntryEnvelope()));
                case SEQUENCE -> throw new StorageFormatException("DuckDB V2 import does not support live sequence catalog entries");
                default -> throw new StorageFormatException("DuckDB V2 import does not support catalog entry type "
                        + envelope.type());
                }
            }
            if (!publicSchema) throw new StorageFormatException("DuckDB V2 import requires default public schema metadata");
            return new Snapshot(tables);
        }
    }

    private static ImportedTable readTable(SingleFileBlockManager manager, DuckDbTableEntryEnvelope entry) {
        DuckDbTableCreateInfo info = entry.createInfo();
        if (!info.qualifiedNamePath().equals(List.of(CATALOG_NAME, LIVE_SCHEMA, info.tableName()))
                || info.temporary() || info.internal()
                || info.onConflict() != DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT
                || !info.sql().isEmpty() || !info.extensionName().isEmpty() || info.columns().isEmpty()) {
            throw new StorageFormatException("DuckDB V2 import table CreateInfo is outside the bounded live shape");
        }
        if (entry.totalRows() < 0 || entry.totalRows() > Integer.MAX_VALUE
                || entry.nextRowId() != entry.totalRows()) {
            throw new StorageFormatException("DuckDB V2 import requires bounded sequential table row IDs");
        }
        List<DuckDbTableCreateInfo.ScalarLogicalType> types = new ArrayList<>(info.columns().size());
        List<ColumnDefinition> columns = new ArrayList<>(info.columns().size());
        for (DuckDbTableCreateInfo.Column column : info.columns()) {
            if (column.category() != DuckDbTableCreateInfo.Category.STANDARD
                    || (column.compression() != DuckDbTableCreateInfo.Compression.UNCOMPRESSED
                    && column.compression() != DuckDbTableCreateInfo.Compression.AUTO)) {
                throw new StorageFormatException("DuckDB V2 import table column shape is unsupported: " + column.name());
            }
            types.add(requireType(column.type()));
            columns.add(new ColumnDefinition(column.name(), typeName(column.type())));
        }
        DuckDbRowGroupHeaders rowGroups = new DuckDbRowGroupHeaderReader(manager,
                new MetaBlockPointer(entry.tablePointer().packedBlockPointer(), (int) entry.tablePointer().offset()), types).read();
        if (rowGroups.groups().size() != 1 || rowGroups.statistics().columns().size() != types.size()) {
            throw new StorageFormatException("DuckDB V2 import requires exactly one primitive row group");
        }
        DuckDbRowGroupHeaders.Header group = rowGroups.groups().getFirst();
        if (group.rowStart() != 0 || group.tupleCount() != entry.totalRows()
                || group.dataPointers().size() != types.size() || !group.deletePointers().isEmpty()
                || group.hasMetadataBlocks() || !group.extraMetadataBlocks().isEmpty()
                || group.hasPerColumnMetadataBlocks() || !group.perColumnMetadataBlocks().isEmpty()) {
            throw new StorageFormatException("DuckDB V2 import row-group envelope is unsupported");
        }
        List<List<Long>> valuesByColumn = new ArrayList<>(types.size());
        for (int index = 0; index < types.size(); index++) {
            DuckDbPrimitiveColumnMetadata metadata = new DuckDbPrimitiveColumnMetadataReader(manager,
                    group.dataPointers().get(index), types.get(index)).read();
            if (entry.totalRows() == 0 && (!metadata.dataSegments().isEmpty() || !metadata.validitySegments().isEmpty())) {
                throw new StorageFormatException("DuckDB V2 import empty table has primitive descriptors");
            }
            DuckDbPrimitiveSegmentScan scan = new DuckDbPrimitiveSegmentScan(manager, types.get(index),
                    metadata.dataSegments(), metadata.validitySegments(), entry.totalRows());
            List<Long> values = new ArrayList<>(Math.toIntExact(entry.totalRows()));
            while (scan.hasNext()) values.addAll(scan.next().values());
            if (values.size() != entry.totalRows()) {
                throw new StorageFormatException("DuckDB V2 import primitive segment count does not match table rows");
            }
            validateStatistics(rowGroups.statistics().columns().get(index), types.get(index), values);
            valuesByColumn.add(values);
        }
        List<List<Object>> rows = new ArrayList<>(Math.toIntExact(entry.totalRows()));
        for (int row = 0; row < entry.totalRows(); row++) {
            List<Object> values = new ArrayList<>(types.size());
            for (int column = 0; column < types.size(); column++) values.add(liveValue(types.get(column), valuesByColumn.get(column).get(row)));
            rows.add(Collections.unmodifiableList(values));
        }
        return new ImportedTable(info.tableName(), columns, List.copyOf(rows));
    }

    private static DuckDbTableCreateInfo.ScalarLogicalType requireType(DuckDbTableCreateInfo.ScalarLogicalType type) {
        if (type != DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN && type != DuckDbTableCreateInfo.ScalarLogicalType.INTEGER
                && type != DuckDbTableCreateInfo.ScalarLogicalType.BIGINT) {
            throw new StorageFormatException("DuckDB V2 import primitive type is unsupported: " + type);
        }
        return type;
    }

    private static TypeName typeName(DuckDbTableCreateInfo.ScalarLogicalType type) {
        return switch (type) {
        case BOOLEAN -> TypeName.BOOLEAN;
        case INTEGER -> TypeName.INT;
        case BIGINT -> TypeName.BIGINT;
        default -> throw new AssertionError(type);
        };
    }

    private static Object liveValue(DuckDbTableCreateInfo.ScalarLogicalType type, Long value) {
        if (value == null) return null;
        return switch (type) {
        case BOOLEAN -> value == 0 ? false : value == 1 ? true : invalidBoolean(value);
        case INTEGER -> Math.toIntExact(value);
        case BIGINT -> value;
        default -> throw new AssertionError(type);
        };
    }

    private static boolean invalidBoolean(long value) {
        throw new StorageFormatException("DuckDB V2 import BOOLEAN payload is not 0 or 1: " + value);
    }

    private static void validateStatistics(DuckDbTableStatistics.Primitive statistics,
                                           DuckDbTableCreateInfo.ScalarLogicalType type, List<Long> values) {
        DuckDbTableStatistics.Kind expected = switch (type) {
        case BOOLEAN -> DuckDbTableStatistics.Kind.BOOLEAN;
        case INTEGER -> DuckDbTableStatistics.Kind.INTEGER;
        case BIGINT -> DuckDbTableStatistics.Kind.BIGINT;
        default -> throw new AssertionError(type);
        };
        boolean hasNull = values.stream().anyMatch(java.util.Objects::isNull);
        boolean hasValue = values.stream().anyMatch(java.util.Objects::nonNull);
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Long value : values) if (value != null) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (statistics.kind() != expected || statistics.hasNull() != hasNull || statistics.hasNoNull() != hasValue
                || statistics.min().isPresent() != hasValue || statistics.max().isPresent() != hasValue
                || (hasValue && (statistics.min().getAsLong() != min || statistics.max().getAsLong() != max))) {
            throw new StorageFormatException("DuckDB V2 import table statistics disagree with primitive values");
        }
    }

    private static void seedWal(Path walPath, Snapshot snapshot) {
        try (WriteAheadLog wal = WriteAheadLog.open(walPath)) {
            Catalog catalog = new Catalog();
            StorageManager storage = new StorageManager();
            TransactionManager transactions = new TransactionManager(wal);
            Transaction transaction = transactions.startTransaction();
            try {
                for (ImportedTable imported : snapshot.tables()) {
                    List<String> name = List.of(LIVE_SCHEMA, imported.name());
                    TableCatalogEntry table = catalog.createTable(transaction, new QualifiedName(name), imported.columns());
                    storage.stageCreate(transaction, table);
                    transactions.recordWrite(transaction, DdlWalPayload.createTable(name, imported.columns()));
                    long rowId = 1;
                    for (List<Object> row : imported.rows()) {
                        long staged = storage.stageAppend(transaction, table, row, rowId);
                        if (staged != rowId) throw new StorageException("DuckDB V2 import row ID sequence diverged");
                        transactions.recordWrite(transaction, DdlWalPayload.primitiveInsert(LIVE_SCHEMA, imported.name(),
                                rowId, table.columns(), row));
                        rowId++;
                    }
                }
                transactions.commit(transaction);
            } catch (RuntimeException failure) {
                if (transaction.state() == TransactionState.ACTIVE) transactions.rollback(transaction);
                throw failure;
            }
        }
    }

    private record Snapshot(List<ImportedTable> tables) {
        private Snapshot {
            tables = List.copyOf(tables);
        }
    }

    private record ImportedTable(String name, List<ColumnDefinition> columns, List<List<Object>> rows) {
        private ImportedTable {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }
}
