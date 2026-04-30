package dev.trentdb.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

final class CatalogSet<T extends CatalogEntry> {
    private final CatalogEntryType entryType;
    private final Map<String, T> entries = new LinkedHashMap<>();

    CatalogSet(CatalogEntryType entryType) {
        this.entryType = entryType;
    }

    void createEntry(T entry) {
        var previous = entries.putIfAbsent(entry.name(), entry);
        if (previous != null) {
            throw new CatalogException(entryTypeName() + " already exists: " + entry.name());
        }
    }

    T getEntry(String name) {
        var entry = entries.get(name);
        if (entry == null) {
            throw new CatalogException(entryTypeName() + " not found: " + name);
        }
        return entry;
    }

    private String entryTypeName() {
        return switch (entryType) {
            case SCHEMA -> "Schema";
            case TABLE -> "Table";
            case COLUMN -> "Column";
        };
    }
}
