package dev.trentdb.planner;

import java.util.List;

public record BoundSelectStatement(
        BoundTableRef from,
        List<BoundExpression> selectList,
        List<String> selectNames,
        BoundExpression where,
        List<BoundOrderByItem> orderBy,
        Long limit
) implements BoundStatement {
    public BoundSelectStatement {
        selectList = List.copyOf(selectList);
        selectNames = List.copyOf(selectNames);
        orderBy = List.copyOf(orderBy);
    }
}
