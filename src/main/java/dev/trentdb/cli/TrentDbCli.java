package dev.trentdb.cli;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.execution.QueryExecutor;
import dev.trentdb.execution.QueryResult;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public final class TrentDbCli {
    private final SqlParser parser = new SqlParser();
    private final LogicalPlanner logicalPlanner = new LogicalPlanner();
    private final Catalog catalog = new Catalog();
    private final TransactionManager transactionManager = new TransactionManager();
    private final StorageManager storageManager = new StorageManager();
    private final dev.trentdb.transaction.Transaction transaction;

    private TrentDbCli() {
        transaction = transactionManager.startTransaction();
        seedCatalog();
    }

    public static void main(String[] args) {
        var cli = new TrentDbCli();
        if (args.length > 0) {
            cli.execute(String.join(" ", args));
            return;
        }
        cli.repl();
    }

    private void repl() {
        try (var scanner = new Scanner(System.in)) {
            var statement = new StringBuilder();
            while (true) {
                System.out.print(statement.isEmpty() ? "trentdb> " : "   ...> ");
                if (!scanner.hasNextLine()) {
                    System.out.println();
                    if (!statement.isEmpty()) {
                        execute(statement.toString());
                    }
                    return;
                }
                var line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (statement.isEmpty() && isExitCommand(line)) {
                    return;
                }
                statement.append(line).append('\n');
                if (line.endsWith(";")) {
                    execute(statement.toString());
                    statement.setLength(0);
                }
            }
        }
    }

    private boolean isExitCommand(String sql) {
        return sql.equalsIgnoreCase("\\q") || sql.equalsIgnoreCase("quit") || sql.equalsIgnoreCase("exit");
    }

    private void execute(String sql) {
        try {
            var result = executeStatement(parser.parse(sql));
            print(result);
        } catch (RuntimeException exception) {
            System.err.println("ERROR: " + exception.getMessage());
        }
    }

    private QueryResult executeStatement(Statement statement) {
        var bound = new Binder(catalog).bind(transaction, statement);
        var logical = logicalPlanner.plan(bound);
        return new QueryExecutor(storageManager).execute(logical);
    }

    private void print(QueryResult result) {
        if (result.columns().isEmpty()) {
            System.out.println("(0 rows)");
            return;
        }

        var widths = columnWidths(result);
        printRow(result.columns(), widths);
        printSeparator(widths);
        for (var row : result.rows()) {
            printRow(row.stream().map(value -> value == null ? "NULL" : value.toString()).toList(), widths);
        }
        System.out.println("(" + result.rows().size() + " rows)");
    }

    private List<Integer> columnWidths(QueryResult result) {
        var widths = new ArrayList<Integer>(result.columns().size());
        for (var column : result.columns()) {
            widths.add(column.length());
        }
        for (var row : result.rows()) {
            for (int index = 0; index < row.size(); index++) {
                var value = row.get(index) == null ? "NULL" : row.get(index).toString();
                widths.set(index, Math.max(widths.get(index), value.length()));
            }
        }
        return widths;
    }

    private void printRow(List<String> values, List<Integer> widths) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                System.out.print(" | ");
            }
            System.out.print(pad(values.get(index), widths.get(index)));
        }
        System.out.println();
    }

    private void printSeparator(List<Integer> widths) {
        for (int index = 0; index < widths.size(); index++) {
            if (index > 0) {
                System.out.print("-+-");
            }
            System.out.print("-".repeat(widths.get(index)));
        }
        System.out.println();
    }

    private String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private void seedCatalog() {
        var table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );
        var storage = storageManager.createTable(table);
        storage.appendRow(List.of(1L, "Alice"));
        storage.appendRow(List.of(2L, "Bob"));
    }
}
