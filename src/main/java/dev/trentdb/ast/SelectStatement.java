package dev.trentdb.ast;

import java.util.List;

public record SelectStatement(
        List<SelectItem> selectItems,
        FromItem from,
        Expression where,
        List<Expression> groupBy,
        List<OrderByItem> orderBy,
        Long limit
) implements Statement {
}
