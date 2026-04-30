package dev.trentdb.ast;

public record TableReference(QualifiedName name, String path, String alias) {
    public TableReference(QualifiedName name, String alias) {
        this(name, null, alias);
    }

    public static TableReference path(String path, String alias) {
        return new TableReference(null, path, alias);
    }

    public boolean isPath() {
        return path != null;
    }
}
