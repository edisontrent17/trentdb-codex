package dev.trentdb.sqllogic;

import java.util.List;
import java.util.Objects;

/** A {@code query} block, including raw expected-result lines and its optional label. */
public record SqlLogicQuery(
        SqlLogicCommand.SourceLocation location,
        List<SqlLogicCondition> conditions,
        String columnTypes,
        String sortOrConnection,
        String label,
        String sql,
        List<String> expectedResults) implements SqlLogicCommand {
    public SqlLogicQuery {
        Objects.requireNonNull(location, "location");
        conditions = List.copyOf(conditions);
        Objects.requireNonNull(columnTypes, "columnTypes");
        Objects.requireNonNull(sql, "sql");
        expectedResults = List.copyOf(expectedResults);
    }

    @Override
    public SqlLogicDirective directive() {
        return SqlLogicDirective.QUERY;
    }
}
