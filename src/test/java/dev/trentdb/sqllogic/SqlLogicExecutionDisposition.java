package dev.trentdb.sqllogic;

/** Explicit C1 accounting categories for commands that the Java runner does not execute yet. */
public enum SqlLogicExecutionDisposition {
    UNSUPPORTED_SQL_EXECUTION,
    UNSUPPORTED_RUNTIME_DIRECTIVE,
    UNSUPPORTED_CONTROL_DIRECTIVE;

    static SqlLogicExecutionDisposition forDirective(SqlLogicDirective directive) {
        return switch (directive) {
            case HASH_THRESHOLD, HALT, MODE, SET, RESET, LOOP, FOREACH, CONCURRENT_LOOP, CONCURRENT_FOREACH,
                    END_LOOP, TAGS, CONTINUE, INCLUDE -> UNSUPPORTED_CONTROL_DIRECTIVE;
            case REQUIRE, REQUIRE_ENV, TEST_ENV, LOAD, RESTART, RECONNECT, SLEEP, UNZIP -> UNSUPPORTED_RUNTIME_DIRECTIVE;
            case SKIP_IF, ONLY_IF, STATEMENT, QUERY -> throw new IllegalArgumentException("not standalone: " + directive);
        };
    }
}
