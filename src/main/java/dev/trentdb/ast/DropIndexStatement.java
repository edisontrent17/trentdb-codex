package dev.trentdb.ast;

import java.util.Objects;

public record DropIndexStatement(QualifiedName name) implements Statement {
    public DropIndexStatement { Objects.requireNonNull(name, "name"); }
}
