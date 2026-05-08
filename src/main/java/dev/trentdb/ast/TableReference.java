package dev.trentdb.ast;

import java.util.List;

public record TableReference(
        QualifiedName name,
        String path,
        SelectStatement subquery,
        String alias,
        List<String> columnAliases
) {
    public TableReference {
        columnAliases = List.copyOf(columnAliases);
    }

    public TableReference(QualifiedName name, String alias) {
        this(name, null, null, alias, List.of());
    }

    public static TableReference path(String path, String alias) {
        return new TableReference(null, path, null, alias, List.of());
    }

    public static TableReference subquery(SelectStatement subquery, String alias, List<String> columnAliases) {
        return new TableReference(null, null, subquery, alias, columnAliases);
    }

    public boolean isPath() {
        return path != null;
    }

    public boolean isSubquery() {
        return subquery != null;
    }
}
