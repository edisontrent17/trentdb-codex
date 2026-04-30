package dev.trentdb.replacement;

import dev.trentdb.catalog.ColumnCatalogEntry;

import java.util.List;

public record ReplacementScan(String path, List<ColumnCatalogEntry> columns, ReplacementScanFunction scanFunction) {
    public ReplacementScan {
        columns = List.copyOf(columns);
    }
}
