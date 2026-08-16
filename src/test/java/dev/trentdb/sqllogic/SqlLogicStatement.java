package dev.trentdb.sqllogic;

import java.util.List;
import java.util.Objects;

/** A {@code statement} block and its expected error text, if supplied upstream. */
public record SqlLogicStatement(
        SqlLogicCommand.SourceLocation location,
        List<SqlLogicCondition> conditions,
        SqlLogicExpectedResult expectedResult,
        String connectionName,
        String sql,
        String expectedError) implements SqlLogicCommand {
    public SqlLogicStatement {
        Objects.requireNonNull(location, "location");
        conditions = List.copyOf(conditions);
        Objects.requireNonNull(expectedResult, "expectedResult");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(expectedError, "expectedError");
    }

    @Override
    public SqlLogicDirective directive() {
        return SqlLogicDirective.STATEMENT;
    }
}
