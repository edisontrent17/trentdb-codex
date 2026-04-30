package dev.trentdb.replacement;

import dev.trentdb.catalog.ColumnCatalogEntry;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.types.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;

final class CsvReplacementScanProvider implements ReplacementScanProvider {
    @Override
    public Optional<ReplacementScan> tryReplace(String path) {
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            return Optional.empty();
        }
        try {
            var header = Files.readAllLines(Path.of(path)).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("CSV file is empty: " + path));
            var names = header.split(",", -1);
            var columns = new ArrayList<ColumnCatalogEntry>(names.length);
            for (int index = 0; index < names.length; index++) {
                columns.add(new ColumnCatalogEntry(names[index].trim(), LogicalType.TEXT, index));
            }
            return Optional.of(new ReplacementScan(path, columns, () -> scanCsv(path, columns)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read CSV file: " + path, exception);
        }
    }

    private java.util.List<DataChunk> scanCsv(String path, java.util.List<ColumnCatalogEntry> columns) {
        try {
            var lines = Files.readAllLines(Path.of(path));
            if (lines.size() <= 1) {
                return java.util.List.of();
            }
            var dataLines = lines.subList(1, lines.size());
            var vectors = new ArrayList<Vector>(columns.size());
            for (var column : columns) {
                var vector = new Vector(LogicalType.TEXT, dataLines.size());
                for (int rowIndex = 0; rowIndex < dataLines.size(); rowIndex++) {
                    var values = dataLines.get(rowIndex).split(",", -1);
                    var value = column.ordinal() < values.length ? values[column.ordinal()] : null;
                    vector.set(rowIndex, value == null || value.isEmpty() ? null : value);
                }
                vectors.add(vector);
            }
            return java.util.List.of(new DataChunk(columns.stream().map(column -> column.name()).toList(), vectors));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read CSV file: " + path, exception);
        }
    }
}
