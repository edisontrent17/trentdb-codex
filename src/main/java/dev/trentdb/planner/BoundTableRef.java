package dev.trentdb.planner;

import dev.trentdb.catalog.TableCatalogEntry;
import dev.trentdb.replacement.ReplacementScan;

public record BoundTableRef(TableCatalogEntry table, ReplacementScan replacementScan, String alias) implements BoundFrom {
    public BoundTableRef(TableCatalogEntry table, String alias) {
        this(table, null, alias);
    }

    public static BoundTableRef replacement(ReplacementScan replacementScan, String alias) {
        return new BoundTableRef(null, replacementScan, alias);
    }

    public boolean isReplacementScan() {
        return replacementScan != null;
    }
}
