package dev.trentdb.sqllogic;

import java.util.List;
import java.util.Objects;

/** A non-SQL sqllogictest directive. Parameters are losslessly whitespace-tokenized as upstream does. */
public record SqlLogicDirectiveCommand(
        SqlLogicDirective directive,
        SqlLogicCommand.SourceLocation location,
        List<SqlLogicCondition> conditions,
        List<String> parameters) implements SqlLogicCommand {
    public SqlLogicDirectiveCommand {
        Objects.requireNonNull(directive, "directive");
        if (directive == SqlLogicDirective.STATEMENT || directive == SqlLogicDirective.QUERY
                || directive == SqlLogicDirective.SKIP_IF || directive == SqlLogicDirective.ONLY_IF) {
            throw new IllegalArgumentException("not a standalone directive: " + directive);
        }
        Objects.requireNonNull(location, "location");
        conditions = List.copyOf(conditions);
        parameters = List.copyOf(parameters);
    }
}
