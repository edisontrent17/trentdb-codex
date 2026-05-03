package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundOrderByItem;

import java.util.List;

public record LogicalOrder(List<BoundOrderByItem> orders, LogicalOperator child) implements LogicalOperator {
    public LogicalOrder {
        orders = List.copyOf(orders);
    }

    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_ORDER_BY;
    }
}
