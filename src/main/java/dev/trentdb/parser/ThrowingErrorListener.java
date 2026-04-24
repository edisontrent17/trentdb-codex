package dev.trentdb.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

final class ThrowingErrorListener extends BaseErrorListener {
    static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

    private ThrowingErrorListener() {
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                            String msg, RecognitionException e) {
        throw new ParsingException("line " + line + ":" + charPositionInLine + " " + msg);
    }
}
