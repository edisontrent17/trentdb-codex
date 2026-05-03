package dev.trentdb.planner;

import java.util.List;

public record BoundSelectStatement(
        BoundFrom from,
        List<BoundExpression> selectList,
        List<String> selectNames,
        BoundExpression where,
        List<BoundExpression> groupBy,
        List<BoundOrderByItem> orderBy,
        Long limit
) implements BoundStatement {
    public BoundSelectStatement {
        selectList = List.copyOf(selectList);
        selectNames = List.copyOf(selectNames);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
    }

    public boolean hasAggregates() {
        for (BoundExpression expression : selectList) {
            if (containsAggregate(expression)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAggregateQuery() {
        return hasAggregates() || !groupBy.isEmpty();
    }

    private boolean containsAggregate(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression ignored -> true;
            case BoundBinaryExpression binary -> containsAggregate(binary.left()) || containsAggregate(binary.right());
            case BoundBetweenExpression between -> containsAggregate(between.input())
                    || containsAggregate(between.lower())
                    || containsAggregate(between.upper());
            case BoundCastExpression cast -> containsAggregate(cast.child());
            case BoundColumnRefExpression ignored -> false;
            case BoundInExpression in -> {
                boolean result = containsAggregate(in.input());
                for (BoundExpression candidate : in.candidates()) {
                    if (containsAggregate(candidate)) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundOutputColumnExpression ignored -> false;
            case BoundFunctionExpression function -> {
                boolean result = false;
                for (BoundExpression argument : function.arguments()) {
                    if (containsAggregate(argument)) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
            case BoundLiteralExpression ignored -> false;
        };
    }
}
