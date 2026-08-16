package dev.trentdb;

import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.ddl.DdlWalRecovery;
import dev.trentdb.execution.DatabaseSession;
import dev.trentdb.execution.QueryResult;
import dev.trentdb.storage.DuckDbV2CheckpointPublisher;
import dev.trentdb.storage.DuckDbV2SnapshotExporter;
import dev.trentdb.storage.DuckDbV2SnapshotImporter;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.TransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Public connection boundary for the supported SQL subset.
 *
 * <p>The catalog and tables are currently in-memory. A caller-supplied WAL path
 * still provides the transaction-intent ordering used by {@link DatabaseSession};
 * it does not make table pages durable.</p>
 */
public final class TrentDbConnection implements AutoCloseable {
    private final DatabaseSession session;
    private final WriteAheadLog wal;
    private final Path temporaryWal;
    private final Catalog catalog;
    private final StorageManager storageManager;
    private final TransactionManager transactionManager;
    private boolean closed;
    private TrentDbConnection(
            Catalog catalog,
            StorageManager storageManager,
            TransactionManager transactionManager,
            WriteAheadLog wal,
            Path temporaryWal
    ) {
        this.wal = Objects.requireNonNull(wal, "wal");
        this.temporaryWal = temporaryWal;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.storageManager = Objects.requireNonNull(storageManager, "storageManager");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        this.session = new DatabaseSession(catalog, storageManager, transactionManager);
    }

    /** Opens a connection after strictly replaying committed TWLF v2 intents into fresh in-memory state. */
    public static TrentDbConnection open(Path walPath) {
        return open(Objects.requireNonNull(walPath, "walPath"), null);
    }

    private static TrentDbConnection open(Path walPath, Path temporaryWal) {
        var wal = WriteAheadLog.open(walPath);
        try {
            var catalog = new Catalog();
            var storageManager = new StorageManager();
            var recovery = new DdlWalRecovery(catalog, storageManager);
            var frames = wal.readAllRecords();
            recovery.replay(wal.recoverCommittedTransactions());
            recovery.reserveInsertRowIds(frames);
            long maxTransactionId = frames.stream().mapToLong(record -> record.transactionId()).max().orElse(0);
            if (maxTransactionId == Long.MAX_VALUE) {
                throw new IllegalStateException("WAL transaction ID space is exhausted");
            }
            var transactionManager = new TransactionManager(wal, maxTransactionId + 1, recovery.recoveredCommitVersion());
            return new TrentDbConnection(catalog, storageManager, transactionManager, wal, temporaryWal);
        } catch (RuntimeException failure) {
            try {
                wal.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Imports a bounded, publisher-produced DuckDB V2 file into a new durable TrentDB WAL and
     * opens the resulting live connection. The source file is only read and is never adopted as
     * TrentDB recovery state.
     */
    public static TrentDbConnection openDuckDbV2(Path source, Path walPath) {
        DuckDbV2SnapshotImporter.importToWal(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(walPath, "walPath"));
        return open(walPath, null);
    }

    /** Opens a connection with an owned temporary WAL, intended for the interactive CLI and examples. */
    public static TrentDbConnection openTemporary() {
        try {
            Path path = Files.createTempFile("trentdb-", ".wal");
            return open(path, path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create temporary WAL", exception);
        }
    }

    /** Parses and executes one supported SQL statement through the transactional pipeline. */
    public synchronized QueryResult execute(String sql) {
        requireOpen();
        return session.execute(sql);
    }

    public synchronized boolean inTransaction() {
        requireOpen();
        return session.inTransaction();
    }

    /**
     * Exports one committed, in-memory snapshot as a bounded DuckDB V2 file. This is a separate
     * low-level interchange path: it neither replaces the WAL nor participates in recovery.
     */
    public synchronized DuckDbV2CheckpointPublisher.Publication exportDuckDbV2(Path target) {
        requireOpen();
        Objects.requireNonNull(target, "target");
        if (session.inTransaction()) {
            throw new IllegalStateException("DuckDB V2 export requires no active transaction");
        }
        return DuckDbV2SnapshotExporter.export(target, catalog, storageManager, transactionManager);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        RuntimeException failure = null;
        try {
            if (session.inTransaction()) {
                session.execute("ROLLBACK");
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            wal.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (temporaryWal != null) {
            try {
                Files.deleteIfExists(temporaryWal);
            } catch (IOException exception) {
                var cleanupFailure = new IllegalStateException("Unable to remove temporary WAL", exception);
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Connection is closed");
        }
    }
}
