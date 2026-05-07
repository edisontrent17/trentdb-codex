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
            case BoundInSubqueryExpression in -> containsAggregate(in.input());
            case BoundSubqueryExpression ignored -> false;
            case BoundOutputColumnExpression ignored -> false;
            case BoundFunctionExpression function -> containsAggregate(function.arguments());
            case BoundLiteralExpression ignored -> false;
            case BoundIntervalExpression ignored -> false;
        };
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
