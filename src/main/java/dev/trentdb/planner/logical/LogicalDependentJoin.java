package dev.trentdb.planner.logical;

import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundSubqueryExpression;

public record LogicalDependentJoin(
        LogicalOperator child,
        BoundExistsSubqueryExpression subquery,
        BoundSubqueryExpression scalarSubquery,
        BoundColumnRefExpression marker,
        Kind kind
) implements LogicalOperator {
    public LogicalDependentJoin(LogicalOperator child, BoundExistsSubqueryExpression subquery, BoundColumnRefExpression marker) {
        this(child, subquery, null, marker, Kind.MARK);
    }

    public static LogicalDependentJoin single(
            LogicalOperator child,
            BoundSubqueryExpression subquery,
            BoundColumnRefExpression marker
    ) {
        return new LogicalDependentJoin(child, null, subquery, marker, Kind.SINGLE);
    }

    @Override
    public LogicalOperatorType type() {
        return LogicalOperatorType.LOGICAL_DELIM_JOIN;
    }

    public enum Kind {
        MARK,
        SINGLE
    }
}
