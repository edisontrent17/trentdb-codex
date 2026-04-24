package dev.duckdbjava.parser;

import dev.duckdbjava.ast.CreateTableStatement;
import dev.duckdbjava.ast.ExplainStatement;
import dev.duckdbjava.ast.InsertStatement;
import dev.duckdbjava.ast.SelectStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SqlParserTest {
    private final SqlParser parser = new SqlParser();

    @Test
    void parsesCreateTable() {
        var statement = parser.parse("CREATE TABLE people (id BIGINT, name TEXT)");

        var create = assertInstanceOf(CreateTableStatement.class, statement);
        assertEquals("people", create.name().last());
        assertEquals(2, create.columns().size());
    }

    @Test
    void parsesInsert() {
        var statement = parser.parse("INSERT INTO people (id, name) VALUES (1, 'alice')");

        var insert = assertInstanceOf(InsertStatement.class, statement);
        assertEquals("people", insert.tableName().last());
        assertEquals(2, insert.columns().size());
        assertEquals(2, insert.values().size());
    }

    @Test
    void parsesSelect() {
        var statement = parser.parse("""
                SELECT p.id, sum(o.total) AS total_spend
                FROM people p
                JOIN orders o ON p.id = o.person_id
                WHERE o.total > 100
                GROUP BY p.id
                ORDER BY total_spend DESC
                LIMIT 10
                """);

        var select = assertInstanceOf(SelectStatement.class, statement);
        assertEquals(2, select.selectItems().size());
        assertEquals(1, select.from().joins().size());
        assertEquals(1, select.groupBy().size());
        assertEquals(1, select.orderBy().size());
        assertEquals(10L, select.limit());
    }

    @Test
    void parsesExplain() {
        var statement = parser.parse("EXPLAIN SELECT * FROM people");

        assertInstanceOf(ExplainStatement.class, statement);
    }
}
