package dev.trentdb.planner;

import dev.trentdb.catalog.TableCatalogEntry;

/** Bound single-column UPDATE. The executor materializes immutable full-row replacements before WAL publication. */
public record BoundUpdateStatement(
        TableCatalogEntry table,
        int targetOrdinal,
        BoundExpression value,
        BoundExpression predicate
) implements BoundStatement {
}
