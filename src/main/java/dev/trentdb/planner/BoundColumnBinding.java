package dev.trentdb.planner;

import dev.trentdb.catalog.ColumnCatalogEntry;

record BoundColumnBinding(String relationName, ColumnCatalogEntry column, int ordinal) {
}
