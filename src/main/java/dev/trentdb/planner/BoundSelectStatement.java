package dev.trentdb.planner;

import dev.trentdb.ast.SetOperation;
import java.util.List;
import java.util.ArrayList;

public record BoundSelectStatement(
        BoundFrom from,
        List<BoundExpression> selectList,
        List<String> selectNames,
        BoundExpression where,
        List<BoundExpression> groupBy,
        BoundExpression having,
        List<BoundOrderByItem> orderBy,
        Long limit,
        SetOperation setOperation,
        BoundSelectStatement left,
        BoundSelectStatement right
) implements BoundStatement {

    public BoundSelectStatement(
            BoundFrom from,
            List<BoundExpression> selectList,
            List<String> selectNames,
            BoundExpression where,
            List<BoundExpression> groupBy,
            BoundExpression having,
            List<BoundOrderByItem> orderBy,
            Long limit
    ) {
        this(from, selectList, selectNames, where, groupBy, having, orderBy, limit, null, null, null);
    }
    public BoundSelectStatement {
        selectList = List.copyOf(selectList);
        selectNames = List.copyOf(selectNames);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
    }
    public static BoundSelectStatement setOperation(
            SetOperation operation,
            BoundSelectStatement left,
            BoundSelectStatement right,
            List<dev.trentdb.types.LogicalType> outputTypes
    ) {
        ArrayList<BoundExpression> outputs = new ArrayList<>(outputTypes.size());
        for (int index = 0; index < outputTypes.size(); index++) {
            outputs.add(new BoundOutputColumnExpression(left.selectNames().get(index), index, outputTypes.get(index)));
        }
        return new BoundSelectStatement(
                null, outputs, left.selectNames(), null, List.of(), null, List.of(), null, operation, left, right
        );
    }

    public BoundSelectStatement withSelectList(List<BoundExpression> selectList) {
        return new BoundSelectStatement(from, selectList, selectNames, where, groupBy, having, orderBy, limit, setOperation, left, right);
    }

    public boolean isCompound() {
        return setOperation != null;
    }


    public boolean hasAggregates() {
        for (BoundExpression expression : selectList) {
            if (containsAggregate(expression)) {
                return true;
            }
        }
        return having != null && containsAggregate(having);
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
            case BoundNullCheckExpression nullCheck -> containsAggregate(nullCheck.expression());
            case BoundCaseExpression caseExpression -> {
                boolean result = containsAggregate(caseExpression.elseExpression());
                for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
                    if (containsAggregate(branch.condition()) || containsAggregate(branch.result())) {
                        result = true;
                        break;
                    }
                }
                yield result;
            }
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
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundInSubqueryExpression in -> containsAggregate(in.input());
            case BoundSubqueryExpression ignored -> false;
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
            case BoundIntervalExpression ignored -> false;
        };
    }
}
