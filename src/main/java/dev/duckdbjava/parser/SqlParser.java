package dev.duckdbjava.parser;

import dev.duckdbjava.ast.Statement;
import dev.duckdbjava.parser.sql.PostgresSubsetSqlLexer;
import dev.duckdbjava.parser.sql.PostgresSubsetSqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

public final class SqlParser {
    public Statement parse(String sql) {
        var lexer = new PostgresSubsetSqlLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        var parser = new PostgresSubsetSqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        return new AstBuilder().build(parser.sqlScript());
    }
}
