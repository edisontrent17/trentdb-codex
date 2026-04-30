package dev.trentdb.planner;

public sealed interface BoundExpression permits BoundBinaryExpression, BoundColumnRefExpression, BoundFunctionExpression, BoundLiteralExpression {
}
