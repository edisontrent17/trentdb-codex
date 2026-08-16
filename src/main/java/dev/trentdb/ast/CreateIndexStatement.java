package dev.trentdb.ast;

import java.util.List;
import java.util.Objects;

/** Non-unique column index definition; physical access paths are separate. */
public record CreateIndexStatement(QualifiedName name, QualifiedName tableName, List<IndexKey> keys) implements Statement {
    public CreateIndexStatement {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tableName, "tableName");
        keys = List.copyOf(keys);
        if (keys.isEmpty()) throw new IllegalArgumentException("Index must contain at least one key");
    }
}
