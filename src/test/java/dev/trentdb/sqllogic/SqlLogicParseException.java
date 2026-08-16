package dev.trentdb.sqllogic;

/** A source-located parse error; callers record it in the corpus report rather than dropping a file. */
public final class SqlLogicParseException extends RuntimeException {
    private final SqlLogicCommand.SourceLocation location;

    public SqlLogicParseException(SqlLogicCommand.SourceLocation location, String message) {
        super(location.file() + ":" + location.line() + ": " + message);
        this.location = location;
    }

    public SqlLogicCommand.SourceLocation location() {
        return location;
    }
}
