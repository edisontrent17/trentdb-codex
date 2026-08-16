package dev.trentdb.sqllogic;

/**
 * C1's explicit execution boundary. No command is implicitly skipped: callers receive a disposition
 * and must account for it in their report before an executor is introduced.
 */
public final class SqlLogicExecutionClassifier {
    public SqlLogicExecutionDisposition classify(SqlLogicCommand command) {
        return switch (command) {
            case SqlLogicStatement ignored -> SqlLogicExecutionDisposition.UNSUPPORTED_SQL_EXECUTION;
            case SqlLogicQuery ignored -> SqlLogicExecutionDisposition.UNSUPPORTED_SQL_EXECUTION;
            case SqlLogicDirectiveCommand directive -> SqlLogicExecutionDisposition.forDirective(directive.directive());
        };
    }
}
