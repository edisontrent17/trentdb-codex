package dev.trentdb.planner;

public sealed interface BoundExpression permits BoundAggregateExpression, BoundBinaryExpression, BoundColumnRefExpression,
        BoundFunctionExpression, BoundLiteralExpression, BoundBetweenExpression, BoundInExpression, BoundCastExpression,
        BoundOutputColumnExpression {
}
