package dev.trentdb.planner;

import dev.trentdb.ast.LiteralKind;
import dev.trentdb.types.LogicalType;

public record BoundLiteralExpression(LogicalType logicalType, Object value, LiteralKind kind) implements BoundExpression {
}
