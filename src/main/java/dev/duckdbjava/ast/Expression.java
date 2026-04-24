package dev.duckdbjava.ast;

public sealed interface Expression permits BinaryExpression, UnaryExpression, LiteralExpression, ColumnReferenceExpression,
        FunctionCallExpression, StarExpression, NullCheckExpression {
}
