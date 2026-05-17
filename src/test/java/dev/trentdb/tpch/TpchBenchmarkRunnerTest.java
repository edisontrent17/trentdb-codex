package dev.trentdb.tpch;

import dev.trentdb.execution.ExecutionProfiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TpchBenchmarkRunnerTest {
    @Test
    void discoversAllTpchCompatibilityQueriesInOrder() {
        TpchBenchmarkRunner.Options options = new TpchBenchmarkRunner.Options(0, 1, Set.of());

        List<TpchBenchmarkRunner.BenchmarkQuery> queries = TpchBenchmarkRunner.queries(options);

        assertEquals(22, queries.size());
        assertEquals("Q01", queries.getFirst().name());
        assertEquals("Q22", queries.getLast().name());
    }

    @Test
    void filtersQueriesByName() {
        TpchBenchmarkRunner.Options options = TpchBenchmarkRunner.Options.parse(
                new String[]{"--query", "1,Q6,22"}
        );

        List<TpchBenchmarkRunner.BenchmarkQuery> queries = TpchBenchmarkRunner.queries(options);

        assertEquals(List.of("Q01", "Q06", "Q22"), queries.stream().map(TpchBenchmarkRunner.BenchmarkQuery::name).toList());
    }

    @Test
    void summarizesOnlyTopLevelQueryExecutorEvents() {
        List<ExecutionProfiler.ProfileEvent> events = List.of(
                new ExecutionProfiler.ProfileEvent("QueryExecutor", "optimize", 10_000_000L, "depth=1"),
                new ExecutionProfiler.ProfileEvent("QueryExecutor", "optimize", 20_000_000L, "depth=2"),
                new ExecutionProfiler.ProfileEvent("QueryExecutor", "total", 30_000_000L, "depth=1"),
                new ExecutionProfiler.ProfileEvent("QueryExecutor", "total", 40_000_000L, "depth=2")
        );

        TpchBenchmarkRunner.ProfileSummary summary = TpchBenchmarkRunner.ProfileSummary.of(events);

        assertEquals(10.0d, summary.optimizerMillis());
        assertEquals(30.0d, summary.queryExecutorMillis());
    }
}
