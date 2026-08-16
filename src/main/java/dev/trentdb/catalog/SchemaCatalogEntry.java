package dev.trentdb.catalog;

/**
 * Immutable schema metadata. Table membership and visibility live in
 * {@link Catalog}, so schema entries cannot bypass transactional boundaries.
 */
public final class SchemaCatalogEntry extends CatalogEntry {
    public SchemaCatalogEntry(String name) {
        super(CatalogEntryType.SCHEMA, name);
    }
}
