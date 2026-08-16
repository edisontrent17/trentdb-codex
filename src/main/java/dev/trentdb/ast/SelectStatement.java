package dev.trentdb.ast;

import java.util.List;

public record SelectStatement(
        List<CommonTableExpression> commonTableExpressions,
        List<SelectItem> selectItems,
        FromItem from,
        Expression where,
        List<Expression> groupBy,
        Expression having,
        List<OrderByItem> orderBy,
        Long limit,
        SetOperation setOperation,
        SelectStatement left,
        SelectStatement right
) implements Statement {

    public SelectStatement(
            List<CommonTableExpression> commonTableExpressions,
            List<SelectItem> selectItems,
            FromItem from,
            Expression where,
            List<Expression> groupBy,
            Expression having,
            List<OrderByItem> orderBy,
            Long limit
    ) {
        this(commonTableExpressions, selectItems, from, where, groupBy, having, orderBy, limit, null, null, null);
    }
    public SelectStatement {
        commonTableExpressions = List.copyOf(commonTableExpressions);
        selectItems = List.copyOf(selectItems);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
        if (setOperation == null && (left != null || right != null)) {
            throw new IllegalArgumentException("A simple SELECT cannot have set-operation children");
        }
        if (setOperation != null && (left == null || right == null)) {
            throw new IllegalArgumentException("A set operation requires left and right SELECT children");
        }
    }

    public static SelectStatement setOperation(SetOperation operation, SelectStatement left, SelectStatement right) {
        return new SelectStatement(List.of(), List.of(), null, null, List.of(), null, List.of(), null, operation, left, right);
    }

    /** Applies clauses whose grammar position is outside the compound query tree. */
    public SelectStatement withOuterClauses(
            List<CommonTableExpression> commonTableExpressions,
            List<OrderByItem> orderBy,
            Long limit
    ) {
        return new SelectStatement(
                commonTableExpressions,
                selectItems,
                from,
                where,
                groupBy,
                having,
                orderBy,
                limit,
                setOperation,
                left,
                right
        );
    }

    public boolean isCompound() {
        return setOperation != null;
    }
}
