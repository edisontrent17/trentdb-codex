package dev.trentdb.parser;

import dev.trentdb.ast.BetweenExpression;
import dev.trentdb.ast.BinaryExpression;
import dev.trentdb.ast.CastExpression;
import dev.trentdb.ast.ColumnReferenceExpression;
import dev.trentdb.ast.CreateTableStatement;
import dev.trentdb.ast.ExplainStatement;
import dev.trentdb.ast.InExpression;
import dev.trentdb.ast.InsertStatement;
import dev.trentdb.ast.LiteralKind;
import dev.trentdb.ast.LiteralExpression;
import dev.trentdb.ast.SelectStatement;
import dev.trentdb.ast.Statement;
import dev.trentdb.ast.TypeName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SqlParserTest {
    private final SqlParser parser = new SqlParser();

    @Test
    void parsesCreateTable() {
        Statement statement = parser.parse("CREATE TABLE People (ID BIGINT, name TEXT, birthdate DATE)");

        CreateTableStatement create = assertInstanceOf(CreateTableStatement.class, statement);
        assertEquals("people", create.name().last());
        assertEquals(3, create.columns().size());
        assertEquals("id", create.columns().getFirst().name());
        assertEquals(TypeName.DATE, create.columns().get(2).type());
    }

    @Test
    void preservesQuotedIdentifierCase() {
        Statement statement = parser.parse("CREATE TABLE \"People\" (\"ID\" BIGINT)");

        CreateTableStatement create = assertInstanceOf(CreateTableStatement.class, statement);
        assertEquals("People", create.name().last());
        assertEquals("ID", create.columns().getFirst().name());
    }

    @Test
    void parsesInsert() {
        Statement statement = parser.parse("INSERT INTO people (id, name) VALUES (1, 'alice')");

        InsertStatement insert = assertInstanceOf(InsertStatement.class, statement);
        assertEquals("people", insert.tableName().last());
        assertEquals(2, insert.columns().size());
        assertEquals(2, insert.values().size());
    }

    @Test
    void parsesSelect() {
        Statement statement = parser.parse("""
                SELECT p.id, sum(o.total) AS total_spend
                FROM people p
                JOIN orders o ON p.id = o.person_id
                WHERE o.total > 100
                GROUP BY p.id
                ORDER BY total_spend DESC
                LIMIT 10
                """);

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        assertEquals(2, select.selectItems().size());
        assertEquals(1, select.from().joins().size());
        assertEquals(1, select.groupBy().size());
        assertEquals(1, select.orderBy().size());
        assertEquals(10L, select.limit());
    }

    @Test
    void parsesExplain() {
        Statement statement = parser.parse("EXPLAIN SELECT * FROM people");

        assertInstanceOf(ExplainStatement.class, statement);
    }

    @Test
    void parsesPathRelation() {
        Statement statement = parser.parse("SELECT * FROM 'people.csv'");

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        assertEquals("people.csv", select.from().base().path());
    }

    @Test
    void parsesBetweenPredicate() {
        Statement statement = parser.parse("SELECT id FROM people WHERE id BETWEEN 1 AND 2");

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        BetweenExpression between = assertInstanceOf(BetweenExpression.class, select.where());
        assertInstanceOf(ColumnReferenceExpression.class, between.input());
        assertInstanceOf(LiteralExpression.class, between.lower());
        assertInstanceOf(LiteralExpression.class, between.upper());
    }

    @Test
    void parsesInPredicate() {
        Statement statement = parser.parse("SELECT id FROM people WHERE id NOT IN (1, 2)");

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        InExpression in = assertInstanceOf(InExpression.class, select.where());
        assertInstanceOf(ColumnReferenceExpression.class, in.input());
        assertEquals(2, in.candidates().size());
        assertEquals(true, in.negated());
    }

    @Test
    void parsesCastExpression() {
        Statement statement = parser.parse("SELECT CAST('1994-01-01' AS date) FROM people");

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        CastExpression cast = assertInstanceOf(CastExpression.class, select.selectItems().getFirst().expression());
        assertEquals(TypeName.DATE, cast.targetType());
        assertInstanceOf(LiteralExpression.class, cast.child());
    }

    @Test
    void parsesDateLiteralExpression() {
        Statement statement = parser.parse("SELECT DATE '1994-01-01' FROM people");

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        CastExpression cast = assertInstanceOf(CastExpression.class, select.selectItems().getFirst().expression());
        assertEquals(TypeName.DATE, cast.targetType());
        assertEquals(
                "1994-01-01",
                assertInstanceOf(LiteralExpression.class, cast.child()).value()
        );
    }

    @Test
    void parsesIntervalDayLiteralExpression() {
        Statement statement = parser.parse("SELECT DATE '1998-12-01' - INTERVAL '90' DAY FROM people");

        SelectStatement select = assertInstanceOf(SelectStatement.class, statement);
        BinaryExpression binary = assertInstanceOf(BinaryExpression.class, select.selectItems().getFirst().expression());
        LiteralExpression literal = assertInstanceOf(LiteralExpression.class, binary.right());
        assertEquals(LiteralKind.INTERVAL_DAYS, literal.kind());
        assertEquals(90L, literal.value());
    }
}
