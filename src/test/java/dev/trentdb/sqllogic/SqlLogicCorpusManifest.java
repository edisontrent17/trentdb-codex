package dev.trentdb.sqllogic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Deterministically discovered, unchanged DuckDB sqllogictest inputs. */
public record SqlLogicCorpusManifest(Path duckdbRoot, List<Path> files) {
    public SqlLogicCorpusManifest {
        Objects.requireNonNull(duckdbRoot, "duckdbRoot");
        files = List.copyOf(files);
    }

    public static SqlLogicCorpusManifest discover(Path projectRoot) throws IOException {
        Path duckdbRoot = projectRoot.resolve("third_party/duckdb").normalize();
        Path testRoot = duckdbRoot.resolve("test");
        if (!Files.isDirectory(testRoot)) {
            throw new IOException("DuckDB test corpus is unavailable at " + testRoot);
        }
        List<Path> files = trackedSqlLogicFiles(duckdbRoot);
        return new SqlLogicCorpusManifest(duckdbRoot, files);
    }

    private static List<Path> trackedSqlLogicFiles(Path duckdbRoot) throws IOException {
        Process process = new ProcessBuilder("git", "-C", duckdbRoot.toString(), "ls-files", "--", "test").start();
        List<String> tracked;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            tracked = reader.lines().sorted().toList();
        }
        try {
            if (process.waitFor() != 0) {
                throw new IOException("git ls-files failed for " + duckdbRoot);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the DuckDB tracked-file inventory", exception);
        }
        return tracked.stream()
                .map(duckdbRoot::resolve)
                .filter(SqlLogicCorpusManifest::isSqlLogicFile)
                .filter(Files::isRegularFile)
                .toList();
    }

    public List<String> relativeFiles() {
        return files.stream()
                .map(path -> duckdbRoot.relativize(path).toString().replace(duckdbRoot.getFileSystem().getSeparator(), "/"))
                .toList();
    }

    private static boolean isSqlLogicFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".test") || name.endsWith(".test_slow") || name.endsWith(".test_coverage");
    }
}
