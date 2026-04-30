package dev.trentdb.planner.logical;

public final class LogicalPlanPrinter {
    public String print(LogicalOperator operator) {
        var builder = new StringBuilder();
        append(operator, builder, 0);
        return builder.toString();
    }

    private void append(LogicalOperator operator, StringBuilder builder, int depth) {
        builder.append("  ".repeat(depth));
        switch (operator) {
            case LogicalExplain explain -> {
                builder.append("LogicalExplain\n");
                append(explain.child(), builder, depth + 1);
            }
            case LogicalProjection projection -> {
                builder.append("LogicalProjection");
                builder.append(" [").append(projection.expressions().size()).append("]\n");
                append(projection.child(), builder, depth + 1);
            }
            case LogicalFilter filter -> {
                builder.append("LogicalFilter\n");
                append(filter.child(), builder, depth + 1);
            }
            case LogicalLimit limit -> {
                builder.append("LogicalLimit ").append(limit.limit()).append("\n");
                append(limit.child(), builder, depth + 1);
            }
            case LogicalGet get -> builder.append("LogicalGet ").append(getName(get)).append("\n");
        }
    }

    private String getName(LogicalGet get) {
        if (get.tableRef().isReplacementScan()) {
            return get.tableRef().replacementScan().path();
        }
        return get.tableRef().table().name();
    }
}
