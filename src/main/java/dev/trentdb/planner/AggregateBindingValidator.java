package dev.trentdb.planner;

import java.util.List;

final class AggregateBindingValidator {
    private AggregateBindingValidator() {
    }

    static void validate(
            List<BoundExpression> selectList,
            List<BoundExpression> groupBy,
            BoundExpression having
    ) {
        boolean aggregateQuery = BoundExpressionInspector.containsAggregate(selectList)
                || BoundExpressionInspector.containsAggregate(having)
                || !groupBy.isEmpty()
                || having != null;
        if (!aggregateQuery) {
            return;
        }
        for (BoundExpression expression : selectList) {
            validateExpression(expression, groupBy);
        }
        if (having != null) {
            validateExpression(having, groupBy);
        }
    }

    private static void validateExpression(BoundExpression expression, List<BoundExpression> groupBy) {
        if (expression instanceof BoundAggregateExpression) {
            return;
        }
        for (BoundExpression group : groupBy) {
            if (group.equals(expression)) {
                return;
            }
        }
        switch (expression) {
            case BoundBinaryExpression binary -> {
                validateExpression(binary.left(), groupBy);
                validateExpression(binary.right(), groupBy);
            }
            case BoundBetweenExpression between -> {
                validateExpression(between.input(), groupBy);
                validateExpression(between.lower(), groupBy);
                validateExpression(between.upper(), groupBy);
            }
            case BoundCastExpression cast -> validateExpression(cast.child(), groupBy);
            case BoundCaseExpression caseExpression -> validateCaseExpression(caseExpression, groupBy);
            case BoundFunctionExpression function -> validateExpressions(function.arguments(), groupBy);
            case BoundInExpression in -> {
                validateExpression(in.input(), groupBy);
                validateExpressions(in.candidates(), groupBy);
            }
            case BoundInSubqueryExpression in -> validateExpression(in.input(), groupBy);
            case BoundSubqueryExpression ignored -> {
            }
            case BoundIntervalExpression ignored -> {
            }
            case BoundLiteralExpression ignored -> {
            }
            case BoundAggregateExpression ignored -> {
            }
            case BoundColumnRefExpression ignored -> throw new BinderException(
                    "Column must appear in GROUP BY or be used in an aggregate function");
            case BoundOutputColumnExpression ignored -> throw new BinderException(
                    "Column must appear in GROUP BY or be used in an aggregate function");
        }
    }

    private static void validateCaseExpression(BoundCaseExpression caseExpression, List<BoundExpression> groupBy) {
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            validateExpression(branch.condition(), groupBy);
            validateExpression(branch.result(), groupBy);
        }
        validateExpression(caseExpression.elseExpression(), groupBy);
    }

    private static void validateExpressions(List<BoundExpression> expressions, List<BoundExpression> groupBy) {
        for (BoundExpression argument : expressions) {
            validateExpression(argument, groupBy);
        }
    }
}
