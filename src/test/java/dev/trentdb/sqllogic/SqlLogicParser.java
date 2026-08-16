package dev.trentdb.sqllogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A lossless structural parser for DuckDB's sqllogictest format. It intentionally stops before SQL
 * parsing or execution: unsupported work is reported by {@link SqlLogicExecutionClassifier}.
 */
public final class SqlLogicParser {
    public SqlLogicScript parse(Path file, Path sourceRoot) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        String source = sourceRoot.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
        return parse(source, new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
    }

    public SqlLogicScript parse(String source, String content) {
        return parse(source, Arrays.asList(content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
    }

    private SqlLogicScript parse(String source, List<String> lines) {
        var commands = new ArrayList<SqlLogicCommand>();
        var pendingConditions = new ArrayList<SqlLogicCondition>();
        int line = 0;
        while (true) {
            line = skipWhitespaceAndComments(lines, line);
            if (line >= lines.size()) {
                break;
            }
            Header header = header(source, lines.get(line), line + 1);
            if (header.directive == SqlLogicDirective.SKIP_IF || header.directive == SqlLogicDirective.ONLY_IF) {
                if (header.parameters.isEmpty()) {
                    throw error(source, line, header.directive.token() + " requires a condition");
                }
                pendingConditions.add(new SqlLogicCondition(
                        header.directive == SqlLogicDirective.SKIP_IF,
                        String.join(" ", header.parameters),
                        new SqlLogicCommand.SourceLocation(source, line + 1)));
                line++;
                continue;
            }
            if (header.directive.singleLine() && !nextLineIsEmptyCommentOrEnd(lines, line)) {
                throw error(source, line, "all single-line directives must be separated by an empty line or comment");
            }
            var location = new SqlLogicCommand.SourceLocation(source, line + 1);
            var conditions = List.copyOf(pendingConditions);
            pendingConditions.clear();
            if (header.directive == SqlLogicDirective.STATEMENT) {
                ParsedStatement statement = parseStatement(source, lines, line, header, location, conditions);
                commands.add(statement.command);
                line = statement.nextLine;
            } else if (header.directive == SqlLogicDirective.QUERY) {
                ParsedQuery query = parseQuery(source, lines, line, header, location, conditions);
                commands.add(query.command);
                line = query.nextLine;
            } else {
                commands.add(new SqlLogicDirectiveCommand(header.directive, location, conditions, header.parameters));
                line++;
            }
        }
        if (!pendingConditions.isEmpty()) {
            SqlLogicCondition condition = pendingConditions.getLast();
            throw new SqlLogicParseException(condition.location(), "condition is not followed by a command");
        }
        return new SqlLogicScript(source, commands);
    }

    private ParsedStatement parseStatement(
            String source,
            List<String> lines,
            int headerLine,
            Header header,
            SqlLogicCommand.SourceLocation location,
            List<SqlLogicCondition> conditions) {
        if (header.parameters.isEmpty()) {
            throw error(source, headerLine, "statement requires an expected result");
        }
        final SqlLogicExpectedResult expected;
        try {
            expected = SqlLogicExpectedResult.fromToken(header.parameters.getFirst());
        } catch (IllegalArgumentException exception) {
            throw error(source, headerLine, exception.getMessage());
        }
        Block block = sqlBlock(lines, headerLine + 1);
        if (block.text.isEmpty()) {
            throw error(source, headerLine + 1, "statement has no SQL text");
        }
        String expectedError = "";
        int next = block.nextLine;
        if (next < lines.size() && lines.get(next).equals("----")) {
            if (expected == SqlLogicExpectedResult.OK || expected == SqlLogicExpectedResult.DEBUG
                    || expected == SqlLogicExpectedResult.DEBUG_SKIP) {
                throw error(source, next, "only error or maybe statements may contain an expected-error block");
            }
            ResultBlock result = resultBlock(lines, next);
            expectedError = String.join("\n", result.values);
            next = result.nextLine;
        }
        String connection = header.parameters.size() > 1 ? header.parameters.get(1) : null;
        return new ParsedStatement(new SqlLogicStatement(location, conditions, expected, connection, block.text, expectedError), next);
    }

    private ParsedQuery parseQuery(
            String source,
            List<String> lines,
            int headerLine,
            Header header,
            SqlLogicCommand.SourceLocation location,
            List<SqlLogicCondition> conditions) {
        if (header.parameters.isEmpty()) {
            throw error(source, headerLine, "query requires a column type string");
        }
        Block block = sqlBlock(lines, headerLine + 1);
        if (block.text.isEmpty()) {
            throw error(source, headerLine + 1, "query has no SQL text");
        }
        ResultBlock result = resultBlock(lines, block.nextLine);
        String sortOrConnection = header.parameters.size() > 1 ? header.parameters.get(1) : null;
        String label = header.parameters.size() > 2 ? header.parameters.get(2) : null;
        return new ParsedQuery(new SqlLogicQuery(
                location, conditions, header.parameters.getFirst(), sortOrConnection, label, block.text, result.values), result.nextLine);
    }

    private static Block sqlBlock(List<String> lines, int line) {
        var sql = new ArrayList<String>();
        while (line < lines.size() && !emptyOrComment(lines.get(line)) && !lines.get(line).equals("----")) {
            sql.add(lines.get(line));
            line++;
        }
        return new Block(String.join("\n", sql), line);
    }

    private static ResultBlock resultBlock(List<String> lines, int line) {
        if (line < lines.size() && lines.get(line).equals("----")) {
            line++;
        }
        var values = new ArrayList<String>();
        while (line < lines.size() && !lines.get(line).isEmpty()) {
            values.add(lines.get(line));
            line++;
        }
        return new ResultBlock(List.copyOf(values), line);
    }

    private static Header header(String source, String line, int lineNumber) {
        List<String> parts = Arrays.stream(line.trim().split("\\s+"))
                .filter(part -> !part.isEmpty())
                .toList();
        if (parts.isEmpty()) {
            throw new SqlLogicParseException(new SqlLogicCommand.SourceLocation(source, lineNumber), "empty header");
        }
        SqlLogicDirective directive = SqlLogicDirective.fromToken(parts.getFirst());
        if (directive == null) {
            throw new SqlLogicParseException(
                    new SqlLogicCommand.SourceLocation(source, lineNumber), "unrecognized sqllogictest directive '" + parts.getFirst() + "'");
        }
        return new Header(directive, List.copyOf(parts.subList(1, parts.size())));
    }

    private static int skipWhitespaceAndComments(List<String> lines, int line) {
        while (line < lines.size() && emptyOrComment(lines.get(line))) {
            line++;
        }
        return line;
    }

    private static boolean nextLineIsEmptyCommentOrEnd(List<String> lines, int line) {
        return line + 1 >= lines.size() || emptyOrComment(lines.get(line + 1));
    }

    private static boolean emptyOrComment(String line) {
        return line.isEmpty() || line.startsWith("#");
    }

    private static SqlLogicParseException error(String source, int zeroBasedLine, String message) {
        return new SqlLogicParseException(new SqlLogicCommand.SourceLocation(source, zeroBasedLine + 1), message);
    }

    private record Header(SqlLogicDirective directive, List<String> parameters) {
    }

    private record Block(String text, int nextLine) {
    }

    private record ResultBlock(List<String> values, int nextLine) {
    }

    private record ParsedStatement(SqlLogicStatement command, int nextLine) {
    }

    private record ParsedQuery(SqlLogicQuery command, int nextLine) {
    }
}
