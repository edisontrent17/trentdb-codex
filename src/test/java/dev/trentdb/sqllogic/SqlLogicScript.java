package dev.trentdb.sqllogic;

import java.util.List;
import java.util.Objects;

/** Immutable parse product for one unchanged upstream sqllogictest file. */
public record SqlLogicScript(String source, List<SqlLogicCommand> commands) {
    public SqlLogicScript {
        Objects.requireNonNull(source, "source");
        commands = List.copyOf(commands);
    }
}
