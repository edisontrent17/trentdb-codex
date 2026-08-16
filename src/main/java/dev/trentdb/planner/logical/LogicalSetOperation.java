package dev.trentdb.planner.logical;

import dev.trentdb.ast.SetOperation;

/** A binary compound-query operator. Its children have the same output schema. */
public record LogicalSetOperation(
        SetOperation operation,
        LogicalOperator left,
        LogicalOperator right
) implements LogicalOperator {
    @Override
    public LogicalOperatorType type() {
        return switch (operation) {
            case UNION -> LogicalOperatorType.LOGICAL_UNION;
            case UNION_ALL -> LogicalOperatorType.LOGICAL_UNION_ALL;
            case EXCEPT -> LogicalOperatorType.LOGICAL_EXCEPT;
            case INTERSECT -> LogicalOperatorType.LOGICAL_INTERSECT;
        };
    }
}
