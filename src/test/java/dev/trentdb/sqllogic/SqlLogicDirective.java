package dev.trentdb.sqllogic;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every sqllogictest header accepted by DuckDB's upstream test/sqlite parser. */
public enum SqlLogicDirective {
    SKIP_IF("skipif", false),
    ONLY_IF("onlyif", false),
    STATEMENT("statement", false),
    QUERY("query", false),
    HASH_THRESHOLD("hash-threshold", true),
    HALT("halt", true),
    MODE("mode", true),
    SET("set", true),
    RESET("reset", true),
    LOOP("loop", true),
    FOREACH("foreach", true),
    CONCURRENT_LOOP("concurrentloop", true),
    CONCURRENT_FOREACH("concurrentforeach", true),
    END_LOOP("endloop", true),
    REQUIRE("require", true),
    REQUIRE_ENV("require-env", true),
    TEST_ENV("test-env", true),
    LOAD("load", true),
    RESTART("restart", true),
    RECONNECT("reconnect", true),
    SLEEP("sleep", true),
    UNZIP("unzip", true),
    TAGS("tags", true),
    CONTINUE("continue", true),
    INCLUDE("include", true);

    private static final Map<String, SqlLogicDirective> BY_TOKEN = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SqlLogicDirective::token, Function.identity()));

    private final String token;
    private final boolean singleLine;

    SqlLogicDirective(String token, boolean singleLine) {
        this.token = token;
        this.singleLine = singleLine;
    }

    public String token() {
        return token;
    }

    public boolean singleLine() {
        return singleLine;
    }

    public static SqlLogicDirective fromToken(String token) {
        return BY_TOKEN.get(token.toLowerCase(Locale.ROOT));
    }
}
