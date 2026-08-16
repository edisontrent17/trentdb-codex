package dev.trentdb.sqllogic;

import java.util.EnumMap;
import java.util.List;

/** Aggregate C2 outcomes; the sum always equals the selected command count. */
public record SqlLogicC2Report(List<SqlLogicC2Result> results, EnumMap<SqlLogicC2Outcome, Integer> counts) {
    public SqlLogicC2Report {
        results = List.copyOf(results);
        counts = new EnumMap<>(counts);
    }

    static SqlLogicC2Report of(List<SqlLogicC2Result> results) {
        var counts = new EnumMap<SqlLogicC2Outcome, Integer>(SqlLogicC2Outcome.class);
        for (SqlLogicC2Outcome outcome : SqlLogicC2Outcome.values()) {
            counts.put(outcome, 0);
        }
        for (SqlLogicC2Result result : results) {
            counts.merge(result.outcome(), 1, Integer::sum);
        }
        return new SqlLogicC2Report(results, counts);
    }
}
