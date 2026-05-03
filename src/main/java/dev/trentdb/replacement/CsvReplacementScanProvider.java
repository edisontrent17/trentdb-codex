package dev.trentdb.replacement;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.types.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CsvReplacementScanProvider implements ReplacementScanProvider {
    @Override
    public Optional<ReplacementScan> tryReplace(String path) {
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            return Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(path));
            String header = lines.stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("CSV file is empty: " + path));
            String[] names = header.split(",", -1);
            List<LogicalType> types = inferTypes(lines.subList(1, lines.size()), names.length);
            ArrayList<ColumnCatalogEntry> columns = new ArrayList<>(names.length);
            for (int index = 0; index < names.length; index++) {
                columns.add(new ColumnCatalogEntry(names[index].trim(), types.get(index), index));
            }
            return Optional.of(new ReplacementScan(path, columns, () -> scanCsv(path, columns)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read CSV file: " + path, exception);
        }
    }

    private List<DataChunk> scanCsv(String path, List<ColumnCatalogEntry> columns) {
        try {
            List<String> lines = Files.readAllLines(Path.of(path));
            if (lines.size() <= 1) {
                return List.of();
            }
            List<String> dataLines = lines.subList(1, lines.size());
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
                        vector.set(rowIndex, parseValue(value, column.logicalType()));
                    }
                    vectors.add(vector);
                }
                chunks.add(new DataChunk(names, vectors));
            }
            return chunks;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read CSV file: " + path, exception);
        }
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

    private Object parseValue(String value, LogicalType type) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (type.equals(LogicalType.DATE)) {
            return LocalDate.parse(value);
        }
        if (type.equals(LogicalType.BIGINT)) {
            return Long.parseLong(value);
        }
        if (type.equals(LogicalType.DOUBLE)) {
            return Double.parseDouble(value);
        }
        return value;
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
}
