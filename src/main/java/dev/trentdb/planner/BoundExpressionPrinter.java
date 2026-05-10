package dev.trentdb.planner;

import java.util.ArrayList;
import java.util.List;

public final class BoundExpressionPrinter {
    public String print(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> aggregate.name() + "("
                    + (aggregate.distinct() ? "DISTINCT " : "")
                    + (aggregate.starArgument() ? "*" : printList(aggregate.arguments())) + ")";
            case BoundBetweenExpression between -> "(" + print(between.input())
                    + " BETWEEN " + print(between.lower()) + " AND " + print(between.upper()) + ")";
            case BoundBinaryExpression binary -> "(" + print(binary.left())
                    + " " + binary.operator().name() + " " + print(binary.right()) + ")";
            case BoundCaseExpression caseExpression -> printCase(caseExpression);
            case BoundCastExpression cast -> "CAST(" + print(cast.child()) + " AS " + cast.logicalType().id().name() + ")";
            case BoundColumnRefExpression column -> column.name() + "#" + column.ordinal();
            case BoundFunctionExpression function -> function.name() + "(" + printList(function.arguments()) + ")";
            case BoundInExpression in -> print(in.input()) + (in.negated() ? " NOT IN " : " IN ")
                    + "(" + printList(in.candidates()) + ")";
            case BoundInSubqueryExpression in -> print(in.input())
                    + (in.negated() ? " NOT IN " : " IN ") + "(SUBQUERY)";
            case BoundExistsSubqueryExpression ignored -> "EXISTS (SUBQUERY)";
            case BoundIntervalExpression interval -> "INTERVAL '" + interval.amount() + "' " + interval.unit().name();
            case BoundLiteralExpression literal -> literal.value() == null ? "NULL" : literal.value().toString();
            case BoundOutputColumnExpression output -> output.name() + "#" + output.ordinal();
            case BoundSubqueryExpression ignored -> "(SUBQUERY)";
        };
    }

    public List<String> printListValues(List<BoundExpression> expressions) {
        ArrayList<String> printed = new ArrayList<>(expressions.size());
        for (BoundExpression expression : expressions) {
            printed.add(print(expression));
        }
        return List.copyOf(printed);
    }

    private String printCase(BoundCaseExpression caseExpression) {
        StringBuilder builder = new StringBuilder("CASE");
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            builder.append(" WHEN ").append(print(branch.condition()));
            builder.append(" THEN ").append(print(branch.result()));
        }
        builder.append(" ELSE ").append(print(caseExpression.elseExpression()));
        builder.append(" END");
        return builder.toString();
    }

    private String printList(List<BoundExpression> expressions) {
        return String.join(", ", printListValues(expressions));
    }
}
