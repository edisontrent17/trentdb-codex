package dev.trentdb.sqllogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SqlLogicHarnessTest {
    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final int PINNED_TRACKED_INPUTS = 5_445;

    @Test
    void parsesAnUnchangedUpstreamFileAndModelsItsDirectives() throws Exception {
        requireCompatibilityCorpus();
        Path duckdbRoot = PROJECT_ROOT.resolve("third_party/duckdb");
        SqlLogicScript script = new SqlLogicParser().parse(
                duckdbRoot.resolve("test/sqlite/tags/tags-1.test"), duckdbRoot);

        assertEquals(3, script.commands().size());
        assertInstanceOf(SqlLogicDirectiveCommand.class, script.commands().get(0));
        assertInstanceOf(SqlLogicStatement.class, script.commands().get(2));
        assertEquals(SqlLogicDirective.TAGS, script.commands().get(0).directive());
        assertEquals(SqlLogicDirective.REQUIRE_ENV, script.commands().get(1).directive());
    }

    @Test
    void recognizesEveryTokenInDuckdbsUpstreamCommandVocabulary() {
        assertEquals(EnumSet.allOf(SqlLogicDirective.class).size(), SqlLogicDirective.values().length);
        for (SqlLogicDirective directive : SqlLogicDirective.values()) {
            assertEquals(directive, SqlLogicDirective.fromToken(directive.token()));
        }
    }

    @Test
    void c1CorpusParseAndClassificationIsOptIn() throws Exception {
        requireCompatibilityCorpus();

        SqlLogicCorpusReport report = SqlLogicCorpusReport.generate(
                PROJECT_ROOT, PROJECT_ROOT.resolve("target/sqllogic"));

        assertEquals(PINNED_TRACKED_INPUTS, report.fileCount(), "C0 tracked corpus inventory changed");
        assertEquals(report.fileCount(), report.parsedFileCount(), report.toJson());
        assertEquals(0, report.parseFailureCount(), report.toJson());
        assertTrue(report.commandCount() > 0, "corpus must contain commands");
        assertEquals(report.commandCount(), report.executionCounts().values().stream().mapToInt(Integer::intValue).sum(),
                "every parsed command must receive an explicit execution classification");
    }
    private static void requireCompatibilityCorpus() {
        Assumptions.assumeTrue(Boolean.getBoolean("duckdb.compatibility.enabled"),
                "enable with -Dduckdb.compatibility.enabled=true or the duckdb-compatibility profile");
        Assumptions.assumeTrue(Files.isDirectory(PROJECT_ROOT.resolve("third_party/duckdb/test")),
                "the DuckDB submodule corpus is unavailable");
    }

}
