package dev.trentdb.ast;

import java.util.Objects;

/** A simple catalog index key: a table column plus its declared ordering. */
public record IndexKey(String columnName, SortDirection direction) {
    public IndexKey {
        Objects.requireNonNull(columnName, "columnName");
        Objects.requireNonNull(direction, "direction");
        if (columnName.isEmpty()) throw new IllegalArgumentException("Index key column name must not be empty");
    }
}
