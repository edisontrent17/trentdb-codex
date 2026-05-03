package dev.trentdb.ast;

public sealed interface Expression permits BinaryExpression, UnaryExpression, LiteralExpression, ColumnReferenceExpression,
        FunctionCallExpression, StarExpression, NullCheckExpression, BetweenExpression, CastExpression {
}
