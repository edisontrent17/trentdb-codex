package dev.trentdb.sqllogic;

import dev.trentdb.ast.CreateIndexStatement;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.DropIndexStatement;
import dev.trentdb.ast.InsertStatement;
import dev.trentdb.ast.SelectStatement;
import dev.trentdb.ast.Statement;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.DatabaseSession;
import dev.trentdb.execution.QueryResult;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.storage.wal.WriteAheadLog;
import dev.trentdb.transaction.TransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Test-only C2 adapter over TrentDB's Java parser/binder/planner/executor. It deliberately supports
 * only the small DDL/DML/query surface implemented by those components and reports every other
 * sqllogictest command as {@link SqlLogicC2Outcome#RUNNER_UNSUPPORTED}.
 */
final class SqlLogicC2Session implements AutoCloseable {
    private final SqlParser parser = new SqlParser();
    private final Catalog catalog = new Catalog();
    private final StorageManager storage = new StorageManager();
    private final Path walPath;
    private final WriteAheadLog wal;
    private final TransactionManager transactionManager;
    private final DatabaseSession databaseSession;
    private final SqlLogicResultComparator comparator = new SqlLogicResultComparator();
    private final Map<String, String> labels = new HashMap<>();
    private int hashThreshold;

    SqlLogicC2Session() {
        try {
            walPath = Files.createTempFile("trentdb-sqllogic-", ".wal");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create C2 test WAL", exception);
        }
        wal = WriteAheadLog.open(walPath);
        transactionManager = new TransactionManager(wal);
        databaseSession = new DatabaseSession(catalog, storage, transactionManager);
    }

    SqlLogicC2Report run(List<SqlLogicCommand> commands) {
        var results = new ArrayList<SqlLogicC2Result>();
        for (SqlLogicCommand command : commands) {
            results.add(run(command));
        }
        return SqlLogicC2Report.of(results);
    }

    SqlLogicC2Result run(SqlLogicCommand command) {
        if (!command.conditions().isEmpty()) {
            return unsupported(command, "conditions require loop/system evaluation");
        }
        return switch (command) {
            case SqlLogicStatement statement -> statement(statement);
            case SqlLogicQuery query -> query(query);
            case SqlLogicDirectiveCommand directive -> directive(directive);
        };
    }

    private SqlLogicC2Result statement(SqlLogicStatement command) {
        if (command.connectionName() != null) {
            return unsupported(command, "named connections are not implemented");
        }
        if (command.expectedResult() == SqlLogicExpectedResult.MAYBE
                || command.expectedResult() == SqlLogicExpectedResult.DEBUG
                || command.expectedResult() == SqlLogicExpectedResult.DEBUG_SKIP) {
            return unsupported(command, "statement mode " + command.expectedResult() + " is not deterministic C2 execution");
        }
        final Statement parsed;
        try {
            parsed = parser.parse(command.sql());
        } catch (RuntimeException exception) {
            return unsupported(command, "TrentDB parser does not support statement text: " + exception.getMessage());
        }
        if (!(parsed instanceof CreateTableStatement) && !(parsed instanceof CreateIndexStatement)
                && !(parsed instanceof DropIndexStatement) && !(parsed instanceof InsertStatement)
                && !(parsed instanceof SelectStatement)) {
            return unsupported(command, "statement type is outside the C2 adapter: " + parsed.getClass().getSimpleName());
        }
        try {
            execute(parsed);
            return command.expectedResult() == SqlLogicExpectedResult.OK
                    ? pass(command, "statement succeeded")
                    : failure(command, "expected an error but statement succeeded");
        } catch (RuntimeException exception) {
            if (command.expectedResult() != SqlLogicExpectedResult.ERROR) {
                return failure(command, "unexpected error: " + exception.getMessage());
            }
            if (!command.expectedError().isEmpty() && !exception.getMessage().contains(command.expectedError())) {
                return failure(command, "error did not contain expected text '" + command.expectedError() + "': " + exception.getMessage());
            }
            return pass(command, "expected error: " + exception.getMessage());
        }
    }

    private SqlLogicC2Result query(SqlLogicQuery command) {
        if (!command.columnTypes().chars().allMatch(type -> type == 'I' || type == 'R' || type == 'T')) {
            return unsupported(command, "query column types are outside SQLLogic I/R/T");
        }
        final Statement parsed;
        try {
            parsed = parser.parse(command.sql());
        } catch (RuntimeException exception) {
            return unsupported(command, "TrentDB parser does not support query text: " + exception.getMessage());
        }
        if (!(parsed instanceof SelectStatement select)) {
            return unsupported(command, "query is not a SELECT statement");
        }
        final QueryResult result;
        try {
            result = executeSelect(select);
        } catch (RuntimeException exception) {
            return failure(command, "query execution error: " + exception.getMessage());
        }
        SqlLogicResultComparator.Comparison comparison = comparator.compare(command, result, hashThreshold, labels);
        return new SqlLogicC2Result(command, comparison.outcome(), comparison.detail());
    }

    private SqlLogicC2Result directive(SqlLogicDirectiveCommand command) {
        return switch (command.directive()) {
            case HASH_THRESHOLD -> hashThreshold(command);
            case RESET -> resetLabel(command);
            default -> unsupported(command, "C2 does not implement directive " + command.directive().token());
        };
    }

    private SqlLogicC2Result hashThreshold(SqlLogicDirectiveCommand command) {
        if (command.parameters().size() != 1) {
            return unsupported(command, "hash-threshold requires one integer parameter");
        }
        try {
            hashThreshold = Integer.parseInt(command.parameters().getFirst());
            return pass(command, "hash threshold set to " + hashThreshold);
        } catch (NumberFormatException exception) {
            return unsupported(command, "hash-threshold is not an integer");
        }
    }

    private SqlLogicC2Result resetLabel(SqlLogicDirectiveCommand command) {
        if (command.parameters().size() != 2 || !command.parameters().getFirst().equalsIgnoreCase("label")) {
            return unsupported(command, "only reset label <name> is implemented");
        }
        labels.remove(command.parameters().get(1));
        return pass(command, "label reset");
    }

    private void execute(Statement statement) {
        databaseSession.execute(statement);
    }

    private QueryResult executeSelect(SelectStatement select) {
        return databaseSession.execute(select);
    }

    @Override
    public void close() {
        try {
            wal.close();
        } finally {
            try {
                Files.deleteIfExists(walPath);
            } catch (IOException ignored) {
                // The temporary C2 WAL is best-effort cleanup only.
            }
        }
    }
    private static SqlLogicC2Result pass(SqlLogicCommand command, String detail) {
        return new SqlLogicC2Result(command, SqlLogicC2Outcome.PASS, detail);
    }

    private static SqlLogicC2Result failure(SqlLogicCommand command, String detail) {
        return new SqlLogicC2Result(command, SqlLogicC2Outcome.ENGINE_FAILURE, detail);
    }

    private static SqlLogicC2Result unsupported(SqlLogicCommand command, String detail) {
        return new SqlLogicC2Result(command, SqlLogicC2Outcome.RUNNER_UNSUPPORTED, detail);
    }
}
