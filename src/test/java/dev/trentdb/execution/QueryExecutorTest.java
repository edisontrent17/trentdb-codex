package dev.trentdb.execution;

import dev.trentdb.ast.ColumnDefinition;
import dev.trentdb.ast.QualifiedName;
import dev.trentdb.ast.TypeName;
import dev.trentdb.catalog.Catalog;
import dev.trentdb.parser.SqlParser;
import dev.trentdb.planner.Binder;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryExecutorTest {
    private final SqlParser parser = new SqlParser();
    private final TransactionManager transactionManager = new TransactionManager();

    @Test
    void executesSelectStar() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT * FROM people");

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(
                List.of(1L, "Alice"),
                List.of(2L, "Bob")
        ), result.rows());
    }

    @Test
    void executesProjection() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT name, id FROM people");

        assertEquals(List.of("name", "id"), result.columns());
        assertEquals(List.of(
                List.of("Alice", 1L),
                List.of("Bob", 2L)
        ), result.rows());
    }

    @Test
    void executesProjectionAliases() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT name AS person_name, lower(name) lowered FROM people LIMIT 1");

        assertEquals(List.of("person_name", "lowered"), result.columns());
        assertEquals(List.of(List.of("Alice", "alice")), result.rows());
    }

    @Test
    void executesArithmeticProjection() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT id + 1 AS next_id, id * 2 doubled FROM people");

        assertEquals(List.of("next_id", "doubled"), result.columns());
        assertEquals(List.of(
                List.of(2L, 2L),
                List.of(3L, 4L)
        ), result.rows());
    }

    @Test
    void executesDivisionAsDouble() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT id / 2 AS half FROM people LIMIT 1");

        assertEquals(List.of("half"), result.columns());
        assertEquals(List.of(List.of(0.5d)), result.rows());
    }

    @Test
    void executesFilter() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT id FROM people WHERE id = 1");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void executesArithmeticFilter() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT name FROM people WHERE id + 1 = 2");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    @Test
    void executesLimit() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT * FROM people LIMIT 1");

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(List.of(1L, "Alice")), result.rows());
    }

    @Test
    void executesZeroLimit() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT * FROM people LIMIT 0");

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void executesFilterBeforeLimit() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT id FROM people WHERE id > 0 LIMIT 1");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void executesLowerFunction() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT lower(name) FROM people");

        assertEquals(List.of("lower"), result.columns());
        assertEquals(List.of(
                List.of("alice"),
                List.of("bob")
        ), result.rows());
    }

    @Test
    void lowerPreservesNulls() {
        var fixture = peopleWithNullFixture();

        var result = execute(fixture, "SELECT lower(name) FROM people");

        assertEquals(List.of("lower"), result.columns());
        assertEquals(List.of(
                List.of("alice"),
                java.util.Arrays.asList((Object) null)
        ), result.rows());
    }

    @Test
    void arithmeticPreservesNulls() {
        var fixture = peopleFixture();

        var result = execute(fixture, "SELECT id + NULL AS value FROM people LIMIT 1");

        assertEquals(List.of("value"), result.columns());
        assertEquals(List.of(java.util.Arrays.asList((Object) null)), result.rows());
    }

    @Test
    void nullComparisonDoesNotPassFilter() {
        var fixture = peopleWithNullFixture();

        var result = execute(fixture, "SELECT id FROM people WHERE name = NULL");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void orUsesSqlThreeValuedLogic() {
        var fixture = peopleWithNullFixture();

        var result = execute(fixture, "SELECT id FROM people WHERE name = 'Alice' OR name = NULL");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(List.of(1L)), result.rows());
    }

    @Test
    void andUsesSqlThreeValuedLogic() {
        var fixture = peopleWithNullFixture();

        var result = execute(fixture, "SELECT id FROM people WHERE name = 'Alice' AND NULL");

        assertEquals(List.of("id"), result.columns());
        assertEquals(List.of(), result.rows());
    }

    @Test
    void executesExplain() {
        var fixture = peopleFixture();

        var result = execute(fixture, "EXPLAIN SELECT id FROM people WHERE id = 1");

        assertEquals(List.of("explain"), result.columns());
        assertEquals("""
                LogicalExplain
                  LogicalProjection [1]
                    LogicalFilter
                      LogicalGet people
                """, result.rows().getFirst().getFirst());
    }

    @Test
    void executesSelectFromCsvPath(@TempDir Path tempDir) throws Exception {
        var csv = tempDir.resolve("people.csv");
        Files.writeString(csv, """
                id,name
                1,Alice
                2,Bob
                """);

        var fixture = emptyFixture();

        var result = execute(fixture, "SELECT name FROM '" + sqlString(csv.toString()) + "' WHERE id = '1'");

        assertEquals(List.of("name"), result.columns());
        assertEquals(List.of(List.of("Alice")), result.rows());
    }

    private QueryResult execute(Fixture fixture, String sql) {
        var statement = parser.parse(sql);
        var bound = new Binder(fixture.catalog).bind(fixture.transaction, statement);
        var logical = new LogicalPlanner().plan(bound);
        return new QueryExecutor(fixture.storageManager).execute(logical);
    }

    private String sqlString(String value) {
        return value.replace("'", "''");
    }

    private Fixture peopleFixture() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        var table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );

        var storageManager = new StorageManager();
        var storage = storageManager.createTable(table);
        storage.appendRow(List.of(1L, "Alice"));
        storage.appendRow(List.of(2L, "Bob"));

        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture peopleWithNullFixture() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        var table = catalog.createTable(
                transaction,
                new QualifiedName(List.of("people")),
                List.of(
                        new ColumnDefinition("id", TypeName.BIGINT),
                        new ColumnDefinition("name", TypeName.TEXT)
                )
        );

        var storageManager = new StorageManager();
        var storage = storageManager.createTable(table);
        storage.appendRow(List.of(1L, "Alice"));
        storage.appendRow(java.util.Arrays.asList(2L, null));

        return new Fixture(catalog, transaction, storageManager);
    }

    private Fixture emptyFixture() {
        var catalog = new Catalog();
        var transaction = transactionManager.startTransaction();
        var storageManager = new StorageManager();
        return new Fixture(catalog, transaction, storageManager);
    }

    private record Fixture(Catalog catalog, dev.trentdb.transaction.Transaction transaction, StorageManager storageManager) {
    }
}
