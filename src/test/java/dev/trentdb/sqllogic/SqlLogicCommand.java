package dev.trentdb.sqllogic;

import java.util.List;
import java.util.Objects;

/** Parsed sqllogictest command. This model deliberately does not execute SQL. */
public sealed interface SqlLogicCommand permits SqlLogicStatement, SqlLogicQuery, SqlLogicDirectiveCommand {
    SqlLogicDirective directive();

    SourceLocation location();

    List<SqlLogicCondition> conditions();

    record SourceLocation(String file, int line) {
        public SourceLocation {
            Objects.requireNonNull(file, "file");
            if (line < 1) {
                throw new IllegalArgumentException("line must be positive");
            }
        }
    }
}
