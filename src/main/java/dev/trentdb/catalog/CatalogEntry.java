package dev.trentdb.catalog;

public abstract class CatalogEntry {
    private final CatalogEntryType type;
    private final String name;

    protected CatalogEntry(CatalogEntryType type, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Catalog entry name must not be blank");
        }
        this.type = type;
        this.name = name;
    }

    public CatalogEntryType type() {
        return type;
    }

    public String name() {
        return name;
    }
}
