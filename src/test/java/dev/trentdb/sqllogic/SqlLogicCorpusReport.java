package dev.trentdb.sqllogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** C0/C1 parse and classification report, with stable JSON for CI consumers. */
public record SqlLogicCorpusReport(
        int fileCount,
        int parsedFileCount,
        int commandCount,
        Map<SqlLogicDirective, Integer> directiveCounts,
        Map<SqlLogicExecutionDisposition, Integer> executionCounts,
        List<ParseFailure> parseFailures) {
    public record ParseFailure(String source, int line, String message) {
    }

    public SqlLogicCorpusReport {
        directiveCounts = Map.copyOf(directiveCounts);
        executionCounts = Map.copyOf(executionCounts);
        parseFailures = List.copyOf(parseFailures);
    }

    public int parseFailureCount() {
        return parseFailures.size();
    }

    public static SqlLogicCorpusReport generate(Path projectRoot, Path outputDirectory) throws IOException {
        SqlLogicCorpusManifest manifest = SqlLogicCorpusManifest.discover(projectRoot);
        SqlLogicParser parser = new SqlLogicParser();
        SqlLogicExecutionClassifier classifier = new SqlLogicExecutionClassifier();
        var directives = zeroed(SqlLogicDirective.class);
        var execution = zeroed(SqlLogicExecutionDisposition.class);
        var failures = new ArrayList<ParseFailure>();
        int parsedFiles = 0;
        int commands = 0;

        for (Path file : manifest.files()) {
            try {
                SqlLogicScript script = parser.parse(file, manifest.duckdbRoot());
                parsedFiles++;
                for (SqlLogicCommand command : script.commands()) {
                    commands++;
                    directives.merge(command.directive(), 1, Integer::sum);
                    for (SqlLogicCondition condition : command.conditions()) {
                        directives.merge(condition.skipIf() ? SqlLogicDirective.SKIP_IF : SqlLogicDirective.ONLY_IF, 1, Integer::sum);
                    }
                    execution.merge(classifier.classify(command), 1, Integer::sum);
                }
            } catch (SqlLogicParseException exception) {
                failures.add(new ParseFailure(
                        exception.location().file(), exception.location().line(), exception.getMessage()));
            } catch (IOException exception) {
                String source = manifest.duckdbRoot().relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                failures.add(new ParseFailure(source, 0, exception.toString()));
            }
        }

        SqlLogicCorpusReport report = new SqlLogicCorpusReport(
                manifest.files().size(), parsedFiles, commands, directives, execution, failures);
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("corpus-manifest.json"), manifestJson(manifest), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("c0-c1-report.json"), report.toJson(), StandardCharsets.UTF_8);
        return report;
    }

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, "files", fileCount, true);
        field(json, "parsedFiles", parsedFileCount, true);
        field(json, "commands", commandCount, true);
        enumCounts(json, "directives", directiveCounts, SqlLogicDirective.values());
        json.append(",\n");
        enumCounts(json, "execution", executionCounts, SqlLogicExecutionDisposition.values());
        json.append(",\n  \"parseFailures\": [");
        for (int index = 0; index < parseFailures.size(); index++) {
            ParseFailure failure = parseFailures.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("\n    {\"source\": \"").append(escape(failure.source())).append("\", \"line\": ")
                    .append(failure.line()).append(", \"message\": \"").append(escape(failure.message())).append("\"}");
        }
        if (!parseFailures.isEmpty()) {
            json.append('\n').append("  ");
        }
        return json.append("]\n}\n").toString();
    }

    private static String manifestJson(SqlLogicCorpusManifest manifest) {
        StringBuilder json = new StringBuilder("{\n  \"files\": [");
        List<String> files = manifest.relativeFiles();
        for (int index = 0; index < files.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("\n    \"").append(escape(files.get(index))).append("\"");
        }
        if (!files.isEmpty()) {
            json.append('\n').append("  ");
        }
        return json.append("]\n}\n").toString();
    }

    private static <E extends Enum<E>> EnumMap<E, Integer> zeroed(Class<E> type) {
        EnumMap<E, Integer> counts = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            counts.put(value, 0);
        }
        return counts;
    }

    private static void enumCounts(StringBuilder json, String name, Map<?, Integer> counts, Enum<?>[] values) {
        json.append("  \"").append(name).append("\": {");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            Enum<?> value = values[index];
            json.append("\n    \"").append(value.name()).append("\": ").append(counts.getOrDefault(value, 0));
        }
        json.append("\n  }");
    }

    private static void field(StringBuilder json, String name, int value, boolean comma) {
        json.append("  \"").append(name).append("\": ").append(value);
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
