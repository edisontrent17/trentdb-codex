package dev.trentdb.planner;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.types.LogicalType;

public record BoundBinaryExpression(
        BoundExpression left,
        BinaryOperator operator,
        BoundExpression right,
        LogicalType logicalType
) implements BoundExpression {
}
