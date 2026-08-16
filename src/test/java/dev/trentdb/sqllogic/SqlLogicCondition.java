package dev.trentdb.sqllogic;

import java.util.Objects;

/** A preceding upstream skipif/onlyif header, retained instead of being silently evaluated. */
public record SqlLogicCondition(boolean skipIf, String expression, SqlLogicCommand.SourceLocation location) {
    public SqlLogicCondition {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(location, "location");
    }
}
