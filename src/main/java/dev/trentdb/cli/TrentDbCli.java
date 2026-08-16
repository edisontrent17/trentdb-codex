package dev.trentdb.cli;

import dev.trentdb.TrentDbConnection;
import dev.trentdb.execution.QueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/** Interactive legacy entrypoint backed by the public transactional connection facade. */
public final class TrentDbCli implements AutoCloseable {
    private final TrentDbConnection connection;

    private TrentDbCli() {
        connection = TrentDbConnection.openTemporary();
        seedCatalog();
    }

    public static void main(String[] args) {
        try (var cli = new TrentDbCli()) {
            if (args.length > 0) {
                cli.execute(String.join(" ", args));
                return;
            }
            cli.repl();
        }
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
                statement.append(line).append((char) 10);
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
            print(connection.execute(sql));
        } catch (RuntimeException exception) {
            System.err.println("ERROR: " + exception.getMessage());
        }
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
        connection.execute("CREATE TABLE people (id BIGINT, name TEXT)");
        connection.execute("INSERT INTO people VALUES (1, 'Alice'), (2, 'Bob')");
    }

    @Override
    public void close() {
        connection.close();
    }
}
