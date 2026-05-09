package dev.trentdb.planner;

import java.util.List;

final class BoundExpressionInspector {
    private BoundExpressionInspector() {
    }

    static boolean containsAggregate(List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (containsAggregate(expression)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsAggregate(BoundExpression expression) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case BoundAggregateExpression ignored -> true;
            case BoundBinaryExpression binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
            case BoundBetweenExpression between -> containsAggregate(between.input())
                    || containsAggregate(between.lower())
                    || containsAggregate(between.upper());
            case BoundCastExpression cast -> containsAggregate(cast.child());
            case BoundCaseExpression caseExpression -> containsCaseAggregate(caseExpression);
            case BoundColumnRefExpression ignored -> false;
            case BoundInExpression in -> containsInAggregate(in);
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundInSubqueryExpression in -> containsAggregate(in.input());
            case BoundSubqueryExpression ignored -> false;
            case BoundOutputColumnExpression ignored -> false;
            case BoundFunctionExpression function -> containsAggregate(function.arguments());
            case BoundLiteralExpression ignored -> false;
            case BoundIntervalExpression ignored -> false;
        };
    }

    static boolean containsColumnOrdinalAtLeast(BoundSelectStatement statement, int ordinal) {
        if (containsColumnOrdinalAtLeast(statement.selectList(), ordinal)) {
            return true;
        }
        return containsColumnOrdinalAtLeastOutsideProjection(statement, ordinal);
    }

    static boolean containsColumnOrdinalAtLeastOutsideProjection(BoundSelectStatement statement, int ordinal) {
        if (containsColumnOrdinalAtLeast(statement.where(), ordinal)) {
            return true;
        }
        if (containsColumnOrdinalAtLeast(statement.groupBy(), ordinal)) {
            return true;
        }
        if (containsColumnOrdinalAtLeast(statement.having(), ordinal)) {
            return true;
        }
        for (BoundOrderByItem item : statement.orderBy()) {
            if (containsColumnOrdinalAtLeast(item.expression(), ordinal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsColumnOrdinalAtLeast(List<BoundExpression> expressions, int ordinal) {
        for (BoundExpression expression : expressions) {
            if (containsColumnOrdinalAtLeast(expression, ordinal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsColumnOrdinalAtLeast(BoundExpression expression, int ordinal) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> containsColumnOrdinalAtLeast(aggregate.arguments(), ordinal);
            case BoundBinaryExpression binary -> containsColumnOrdinalAtLeast(binary.left(), ordinal)
                    || containsColumnOrdinalAtLeast(binary.right(), ordinal);
            case BoundBetweenExpression between -> containsColumnOrdinalAtLeast(between.input(), ordinal)
                    || containsColumnOrdinalAtLeast(between.lower(), ordinal)
                    || containsColumnOrdinalAtLeast(between.upper(), ordinal);
            case BoundCastExpression cast -> containsColumnOrdinalAtLeast(cast.child(), ordinal);
            case BoundCaseExpression caseExpression -> containsCaseColumnOrdinalAtLeast(caseExpression, ordinal);
            case BoundColumnRefExpression column -> column.ordinal() >= ordinal;
            case BoundInExpression in -> containsInColumnOrdinalAtLeast(in, ordinal);
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundInSubqueryExpression in -> containsColumnOrdinalAtLeast(in.input(), ordinal);
            case BoundSubqueryExpression ignored -> false;
            case BoundOutputColumnExpression output -> output.ordinal() >= ordinal;
            case BoundFunctionExpression function -> containsColumnOrdinalAtLeast(function.arguments(), ordinal);
            case BoundLiteralExpression ignored -> false;
            case BoundIntervalExpression ignored -> false;
        };
    }

    private static boolean containsCaseColumnOrdinalAtLeast(BoundCaseExpression caseExpression, int ordinal) {
        if (containsColumnOrdinalAtLeast(caseExpression.elseExpression(), ordinal)) {
            return true;
        }
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (containsColumnOrdinalAtLeast(branch.condition(), ordinal)
                    || containsColumnOrdinalAtLeast(branch.result(), ordinal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInColumnOrdinalAtLeast(BoundInExpression in, int ordinal) {
        if (containsColumnOrdinalAtLeast(in.input(), ordinal)) {
            return true;
        }
        return containsColumnOrdinalAtLeast(in.candidates(), ordinal);
    }

    private static boolean containsCaseAggregate(BoundCaseExpression caseExpression) {
        if (containsAggregate(caseExpression.elseExpression())) {
            return true;
        }
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (containsAggregate(branch.condition()) || containsAggregate(branch.result())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInAggregate(BoundInExpression in) {
        if (containsAggregate(in.input())) {
            return true;
        }
        return containsAggregate(in.candidates());
    }
}
