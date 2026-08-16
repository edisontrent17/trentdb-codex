package dev.trentdb.sqllogic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SqlLogicC2SessionTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void executesSupportedStatementsAndComparesValuesHashesLabelsAndErrors() {
        String hash = SqlLogicResultComparator.hash(List.of("1", "2"));
        SqlLogicScript script = new SqlLogicParser().parse("c2-inline.test", """
                statement ok
                CREATE TABLE c2_numbers (id BIGINT, value TEXT)

                statement ok
                INSERT INTO c2_numbers(value, id) VALUES ('one', 1)

                statement ok
                INSERT INTO c2_numbers(value, id) VALUES ('two', 2)

                query IT rowsort
                SELECT id, value FROM c2_numbers
                ----
                1\tone
                2\ttwo

                hash-threshold 1

                query I valuesort
                SELECT id FROM c2_numbers
                ----
                %s

                query I nosort stable-label
                SELECT id FROM c2_numbers ORDER BY id
                ----
                ignored-by-upstream-label-rule

                query I nosort stable-label
                SELECT id FROM c2_numbers ORDER BY id
                ----
                ignored-by-upstream-label-rule

                statement error
                CREATE TABLE c2_numbers (id BIGINT, value TEXT)
                ----
                already exists

                skipif duckdb
                statement ok
                CREATE TABLE skipped_by_condition (id BIGINT)
                """.formatted(hash));

        SqlLogicC2Report report;
        try (SqlLogicC2Session session = new SqlLogicC2Session()) {
            report = session.run(script.commands());
        }

        assertEquals(10, report.results().size());
        assertEquals(9, report.counts().get(SqlLogicC2Outcome.PASS));
        assertEquals(0, report.counts().get(SqlLogicC2Outcome.ENGINE_FAILURE));
        assertEquals(1, report.counts().get(SqlLogicC2Outcome.RUNNER_UNSUPPORTED));
    }

    @Test
    void executesCreateAndDropIndexThroughTransactionalSessionFacade() {
        SqlLogicScript script = new SqlLogicParser().parse("c2-index.test", """
                statement ok
                CREATE TABLE c2_index_values (id BIGINT, value TEXT)

                statement ok
                INSERT INTO c2_index_values VALUES (1, 'one')

                statement ok
                CREATE INDEX c2_index_values_id ON c2_index_values(id DESC)

                statement ok
                DROP INDEX c2_index_values_id

                statement ok
                CREATE INDEX c2_index_values_id ON c2_index_values(id ASC)

                query I rowsort
                SELECT id FROM c2_index_values
                ----
                1
                """);

        SqlLogicC2Report report;
        try (SqlLogicC2Session session = new SqlLogicC2Session()) {
            report = session.run(script.commands());
        }

        assertEquals(6, report.results().size());
        assertEquals(6, report.counts().get(SqlLogicC2Outcome.PASS));
        assertEquals(0, report.counts().get(SqlLogicC2Outcome.ENGINE_FAILURE));
        assertEquals(0, report.counts().get(SqlLogicC2Outcome.RUNNER_UNSUPPORTED));
    }

    @Test
    void select4SetOperationCheckpointPassesThroughCommand1143() throws Exception {
        requireCompatibilityCorpus();
        Path duckdbRoot = PROJECT_ROOT.resolve("third_party/duckdb");
        SqlLogicScript script = new SqlLogicParser().parse(
                duckdbRoot.resolve("test/sqlite/select4.test_slow"), duckdbRoot);
        List<SqlLogicCommand> slice = script.commands().subList(0, 1143);

        assertEquals(1143, slice.size(), "select4 set-operation checkpoint boundary changed");
        SqlLogicC2Report report;
        try (SqlLogicC2Session session = new SqlLogicC2Session()) {
            report = session.run(slice);
        }

        assertEquals(1143, report.results().size());
        assertEquals(1143, report.counts().get(SqlLogicC2Outcome.PASS));
        assertEquals(0, report.counts().get(SqlLogicC2Outcome.ENGINE_FAILURE));
        assertEquals(0, report.counts().get(SqlLogicC2Outcome.RUNNER_UNSUPPORTED));
        assertEquals(0, report.counts().get(SqlLogicC2Outcome.ENVIRONMENT_BLOCKED));
    }


    @Test
    void c2RunsDeterministicCapabilitySelectedUpstreamCheckpoint() throws Exception {
        requireCompatibilityCorpus();
        Path duckdbRoot = PROJECT_ROOT.resolve("third_party/duckdb");
        List<UpstreamC2Checkpoint> checkpoint = List.of(
                new UpstreamC2Checkpoint("test/sqlite/select1.test_slow", 1031),
                new UpstreamC2Checkpoint("test/sqlite/select2.test_slow", 1032),
                new UpstreamC2Checkpoint("test/sqlite/select4.test_slow", 1143),
                new UpstreamC2Checkpoint("test/sqlite/select3.test_slow", 3352));

        int totalCommands = 0;
        for (UpstreamC2Checkpoint item : checkpoint) {
            SqlLogicScript script = new SqlLogicParser().parse(duckdbRoot.resolve(item.source()), duckdbRoot);
            List<SqlLogicCommand> slice = script.commands().subList(0, item.commandCount());

            assertEquals(item.commandCount(), slice.size(), item.source() + " checkpoint boundary changed");
            SqlLogicC2Report report;
            try (SqlLogicC2Session session = new SqlLogicC2Session()) {
                report = session.run(slice);
            }
            assertEquals(slice.size(), report.results().size(), item.source());
            assertEquals(slice.size(), report.counts().values().stream().mapToInt(Integer::intValue).sum(), item.source());
            assertEquals(item.commandCount(), report.counts().get(SqlLogicC2Outcome.PASS), item.source());
            assertEquals(0, report.counts().get(SqlLogicC2Outcome.ENGINE_FAILURE), item.source());
            assertEquals(0, report.counts().get(SqlLogicC2Outcome.RUNNER_UNSUPPORTED), item.source());
            assertEquals(0, report.counts().get(SqlLogicC2Outcome.ENVIRONMENT_BLOCKED), item.source());
            totalCommands += slice.size();
        }
        assertEquals(6558, totalCommands, "C2 capability checkpoint size changed");
    }












    private record UpstreamC2Checkpoint(String source, int commandCount) {}

    private static void requireCompatibilityCorpus() {
        Assumptions.assumeTrue(Boolean.getBoolean("duckdb.compatibility.enabled"),
                "enable with -Dduckdb.compatibility.enabled=true or the duckdb-compatibility profile");
        Assumptions.assumeTrue(Files.isDirectory(PROJECT_ROOT.resolve("third_party/duckdb/test")),
                "the DuckDB submodule corpus is unavailable");
    }
}
