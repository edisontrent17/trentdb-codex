package dev.trentdb.sqllogic;

/** DuckDB statement result mode. */
public enum SqlLogicExpectedResult {
    OK,
    ERROR,
    MAYBE,
    DEBUG,
    DEBUG_SKIP;

    static SqlLogicExpectedResult fromToken(String token) {
        return switch (token) {
            case "ok" -> OK;
            case "error" -> ERROR;
            case "maybe" -> MAYBE;
            case "debug" -> DEBUG;
            case "debug_skip" -> DEBUG_SKIP;
            default -> throw new IllegalArgumentException("unknown statement result: " + token);
        };
    }
}
