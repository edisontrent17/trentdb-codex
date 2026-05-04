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
                vectors.add(new Vector(column.logicalType(), size));
            }
            for (int rowIndex = 0; rowIndex < size; rowIndex++) {
                String[] values = splitCsvLine(dataLines.get(offset + rowIndex));
                for (ColumnCatalogEntry column : columns) {
                    String value = column.ordinal() < values.length ? values[column.ordinal()] : null;
                    writeValue(vectors.get(column.ordinal()), rowIndex, value, column.logicalType());
                }
            }
            chunks.add(new DataChunk(names, vectors));
        }
        return List.copyOf(chunks);
    }

    private List<LogicalType> inferTypes(List<String> lines, int columnCount) {
        ArrayList<TypeInference> states = new ArrayList<>(columnCount);
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            states.add(new TypeInference());
        }
        for (String line : lines) {
            String[] values = splitCsvLine(line);
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                String value = columnIndex < values.length ? values[columnIndex] : null;
                states.get(columnIndex).observe(value);
            }
        }
        ArrayList<LogicalType> types = new ArrayList<>(columnCount);
        for (TypeInference state : states) {
            types.add(state.logicalType());
        }
        return types;
    }

    private String[] splitCsvLine(String line) {
        int valueCount = 1;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == ',') {
                valueCount++;
            }
        }
        String[] values = new String[valueCount];
        int start = 0;
        int valueIndex = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == ',') {
                values[valueIndex] = line.substring(start, index);
                valueIndex++;
                start = index + 1;
            }
        }
        values[valueIndex] = line.substring(start);
        return values;
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
        if (value.length() != 10 || value.charAt(4) != '-' || value.charAt(7) != '-') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (index == 4 || index == 7) {
                continue;
            }
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean canParseBigint(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int start = value.charAt(0) == '-' || value.charAt(0) == '+' ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int index = start; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean canParseDouble(String value) {
        if (!looksLikeDouble(value)) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean looksLikeDouble(String value) {
        if (value.isEmpty()) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasDecimal = false;
        boolean hasExponent = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isDigit(ch)) {
                hasDigit = true;
                continue;
            }
            if ((ch == '+' || ch == '-') && (index == 0 || isExponent(value.charAt(index - 1)))) {
                continue;
            }
            if (ch == '.' && !hasDecimal && !hasExponent) {
                hasDecimal = true;
                continue;
            }
            if (isExponent(ch) && !hasExponent && hasDigit) {
                hasExponent = true;
                hasDigit = false;
                continue;
            }
            return false;
        }
        return hasDigit;
    }

    private boolean isExponent(char ch) {
        return ch == 'e' || ch == 'E';
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

    private final class TypeInference {
        private boolean couldBeDate = true;
        private boolean couldBeBigint = true;
        private boolean couldBeDouble = true;

        private void observe(String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            couldBeDate = couldBeDate && canParseDate(value);
            couldBeBigint = couldBeBigint && canParseBigint(value);
            couldBeDouble = couldBeDouble && canParseDouble(value);
        }

        private LogicalType logicalType() {
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
    }
}
