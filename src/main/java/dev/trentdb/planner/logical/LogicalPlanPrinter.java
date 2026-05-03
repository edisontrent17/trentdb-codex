package dev.trentdb.planner.logical;

public final class LogicalPlanPrinter {
    public String print(LogicalOperator operator) {
        StringBuilder builder = new StringBuilder();
        append(operator, builder, 0);
        return builder.toString();
    }

    private void append(LogicalOperator operator, StringBuilder builder, int depth) {
        builder.append("  ".repeat(depth));
        switch (operator) {
            case LogicalAggregate aggregate -> {
                builder.append("LogicalAggregate");
                builder.append(" groups=[").append(aggregate.groups().size()).append("]");
                builder.append(" expressions=[").append(aggregate.selectList().size()).append("]\n");
                append(aggregate.child(), builder, depth + 1);
            }
            case LogicalExplain explain -> {
                builder.append("LogicalExplain\n");
                append(explain.child(), builder, depth + 1);
            }
            case LogicalProjection projection -> {
                builder.append("LogicalProjection");
                builder.append(" [").append(projection.expressions().size()).append("]\n");
                append(projection.child(), builder, depth + 1);
            }
            case LogicalJoin join -> {
                builder.append("LogicalComparisonJoin\n");
                appendGet(join.left(), builder, depth + 1);
                appendGet(join.right(), builder, depth + 1);
            }
            case LogicalFilter filter -> {
                builder.append("LogicalFilter\n");
                append(filter.child(), builder, depth + 1);
            }
            case LogicalLimit limit -> {
                builder.append("LogicalLimit ").append(limit.limit()).append("\n");
                append(limit.child(), builder, depth + 1);
            }
            case LogicalOrder order -> {
                builder.append("LogicalOrder");
                builder.append(" [").append(order.orders().size()).append("]\n");
                append(order.child(), builder, depth + 1);
            }
            case LogicalGet get -> builder.append("LogicalGet ").append(getName(get.tableRef())).append("\n");
        }
    }

    private void appendGet(dev.trentdb.planner.BoundTableRef tableRef, StringBuilder builder, int depth) {
        builder.append("  ".repeat(depth));
        builder.append("LogicalGet ").append(getName(tableRef)).append("\n");
    }

    private String getName(dev.trentdb.planner.BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().path();
        }
        return tableRef.table().name();
    }
}
