package dev.trentdb.ast;

import java.util.List;

public record QualifiedName(List<String> parts) {
    public QualifiedName {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("Qualified name must contain at least one part");
        }
    }

    public String last() {
        return parts.get(parts.size() - 1);
    }
}
