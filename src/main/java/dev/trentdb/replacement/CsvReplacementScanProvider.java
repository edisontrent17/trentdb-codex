package dev.trentdb.replacement;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionProfiler;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.types.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class CsvReplacementScanProvider implements ReplacementScanProvider {
    private static final ConcurrentMap<String, CachedCsv> CACHE = new ConcurrentHashMap<>();

    @Override
    public Optional<ReplacementScan> tryReplace(String path) {
        if (!path.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return Optional.empty();
        }
        Path normalizedPath = Path.of(path).toAbsolutePath().normalize();
        String cacheKey = normalizedPath.toString();
        FileVersion version = readVersion(normalizedPath, path);
        CachedCsv cached = CACHE.get(cacheKey);
        if (cached != null && cached.version().equals(version)) {
            ExecutionProfiler.log(
                    "CsvReplacementScanProvider",
                    "cache_hit",
                    ExecutionProfiler.start(),
                    "path=" + path + " rows=" + cached.rowCount() + " chunks=" + cached.chunks().size()
            );
            return Optional.of(new ReplacementScan(path, cached.columns(), cached::chunks));
        }
        CachedCsv loaded = loadCsv(path, normalizedPath, version);
        CACHE.put(cacheKey, loaded);
        return Optional.of(new ReplacementScan(path, loaded.columns(), loaded::chunks));
    }

    private FileVersion readVersion(Path normalizedPath, String originalPath) {
        try {
            long size = Files.size(normalizedPath);
            FileTime modified = Files.getLastModifiedTime(normalizedPath);
            return new FileVersion(size, modified.toMillis());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read CSV file: " + originalPath, exception);
        }
    }

    private CachedCsv loadCsv(String originalPath, Path normalizedPath, FileVersion version) {
        try {
            long readStart = ExecutionProfiler.start();
            List<String> lines = Files.readAllLines(normalizedPath);
            ExecutionProfiler.log(
                    "CsvReplacementScanProvider",
                    "read_lines",
                    readStart,
                    "path=" + originalPath + " rows=" + Math.max(0, lines.size() - 1)
            );

            String header = lines.stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("CSV file is empty: " + originalPath));
            String[] names = header.split(",", -1);

            long schemaStart = ExecutionProfiler.start();
            List<String> dataLines = lines.subList(1, lines.size());
            List<LogicalType> types = inferTypes(dataLines, names.length);
            ArrayList<ColumnCatalogEntry> columns = new ArrayList<>(names.length);
            for (int index = 0; index < names.length; index++) {
                columns.add(new ColumnCatalogEntry(names[index].trim(), types.get(index), index));
            }
            ExecutionProfiler.log(
                    "CsvReplacementScanProvider",
                    "schema",
                    schemaStart,
                    "path=" + originalPath + " rows=" + dataLines.size() + " columns=" + names.length
            );

            long chunkStart = ExecutionProfiler.start();
            List<DataChunk> chunks = materializeChunks(dataLines, columns);
            ExecutionProfiler.log(
                    "CsvReplacementScanProvider",
                    "materialize_chunks",
                    chunkStart,
                    "path=" + originalPath + " chunks=" + chunks.size() + " rows=" + dataLines.size()
            );
            ExecutionProfiler.log(
                    "CsvReplacementScanProvider",
                    "cache_miss",
                    ExecutionProfiler.start(),
                    "path=" + originalPath + " rows=" + dataLines.size() + " chunks=" + chunks.size()
            );
            return new CachedCsv(version, columns, chunks, dataLines.size());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read CSV file: " + originalPath, exception);
        }
    }

    private List<DataChunk> materializeChunks(List<String> dataLines, List<ColumnCatalogEntry> columns) {
        if (dataLines.isEmpty()) {
            return List.of();
        }
        ArrayList<DataChunk> chunks = new ArrayList<>();
        List<String> names = columns.stream().map(ColumnCatalogEntry::name).toList();
        for (int offset = 0; offset < dataLines.size(); offset += InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
            int size = Math.min(InMemoryTableStorage.STANDARD_VECTOR_SIZE, dataLines.size() - offset);
            ArrayList<Vector> vectors = new ArrayList<>(columns.size());
            for (ColumnCatalogEntry column : columns) {
                Vector vector = new Vector(column.logicalType(), size);
                for (int rowIndex = 0; rowIndex < size; rowIndex++) {
                    String[] values = dataLines.get(offset + rowIndex).split(",", -1);
                    String value = column.ordinal() < values.length ? values[column.ordinal()] : null;
                    writeValue(vector, rowIndex, value, column.logicalType());
                }
                vectors.add(vector);
            }
            chunks.add(new DataChunk(names, vectors));
        }
        return List.copyOf(chunks);
    }

    private List<LogicalType> inferTypes(List<String> lines, int columnCount) {
        ArrayList<LogicalType> types = new ArrayList<>(columnCount);
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            types.add(inferType(lines, columnIndex));
        }
        return types;
    }

    private LogicalType inferType(List<String> lines, int columnIndex) {
        boolean couldBeDate = true;
        boolean couldBeBigint = true;
        boolean couldBeDouble = true;
        for (String line : lines) {
            String[] values = line.split(",", -1);
            String value = columnIndex < values.length ? values[columnIndex] : null;
            if (value == null || value.isEmpty()) {
                continue;
            }
            couldBeDate = couldBeDate && canParseDate(value);
            couldBeBigint = couldBeBigint && canParseBigint(value);
            couldBeDouble = couldBeDouble && canParseDouble(value);
        }
        if (couldBeDate) {
            return LogicalType.DATE;
        }
        if (couldBeBigint) {
            return LogicalType.BIGINT;
        }
        if (couldBeDouble) {
            return LogicalType.DOUBLE;
        }
        return LogicalType.TEXT;
    }

    private void writeValue(Vector vector, int rowIndex, String value, LogicalType type) {
        if (value == null || value.isEmpty()) {
            vector.setNull(rowIndex);
            return;
        }
        if (type.equals(LogicalType.DATE)) {
            vector.setDate(rowIndex, LocalDate.parse(value));
            return;
        }
        if (type.equals(LogicalType.BIGINT)) {
            vector.setBigint(rowIndex, Long.parseLong(value));
            return;
        }
        if (type.equals(LogicalType.DOUBLE)) {
            vector.setDouble(rowIndex, Double.parseDouble(value));
            return;
        }
        vector.setText(rowIndex, value);
    }

    private boolean canParseDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean canParseBigint(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean canParseDouble(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private record FileVersion(long size, long modifiedMillis) {
    }

    private record CachedCsv(
            FileVersion version,
            List<ColumnCatalogEntry> columns,
            List<DataChunk> chunks,
            int rowCount
    ) {
        private CachedCsv {
            columns = List.copyOf(columns);
            chunks = List.copyOf(chunks);
        }
    }
}
