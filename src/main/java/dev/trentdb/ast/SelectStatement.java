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
        Long limit
) implements Statement {
    public SelectStatement {
        commonTableExpressions = List.copyOf(commonTableExpressions);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
    }
}
