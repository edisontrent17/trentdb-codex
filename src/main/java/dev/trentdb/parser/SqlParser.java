package dev.trentdb.parser;

import dev.trentdb.ast.Statement;
import dev.trentdb.parser.sql.TrentDbSqlLexer;
import dev.trentdb.parser.sql.TrentDbSqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

public final class SqlParser {
    public Statement parse(String sql) {
        TrentDbSqlLexer lexer = new TrentDbSqlLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        TrentDbSqlParser parser = new TrentDbSqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        return new AstBuilder().build(parser.sqlScript());
    }
}
