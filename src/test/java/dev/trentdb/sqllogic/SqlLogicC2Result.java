package dev.trentdb.sqllogic;

import java.util.Objects;

/** One explicitly accounted C2 command result. */
public record SqlLogicC2Result(SqlLogicCommand command, SqlLogicC2Outcome outcome, String detail) {
    public SqlLogicC2Result {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(detail, "detail");
    }
}
