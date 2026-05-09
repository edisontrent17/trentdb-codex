package dev.trentdb.planner;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.UnaryOperator;
import dev.trentdb.types.LogicalType;

final class UnaryExpressionBinder {
    private UnaryExpressionBinder() {
    }

    static BoundExpression bind(UnaryOperator operator, BoundExpression child) {
        LogicalType childType = BoundExpressionTypes.logicalType(child);
        return switch (operator) {
            case PLUS -> bindPlus(child, childType);
            case MINUS -> bindMinus(child, childType);
            case NOT -> bindNot(child, childType);
        };
    }

    private static BoundExpression bindPlus(BoundExpression child, LogicalType childType) {
        if (BoundExpressionTypes.isNumeric(childType) || BoundExpressionTypes.isNull(childType)) {
            return child;
        }
        throw new BinderException("Unary plus requires a numeric operand but got "
                + BoundExpressionTypes.typeName(childType));
    }

    private static BoundExpression bindMinus(BoundExpression child, LogicalType childType) {
        if (!BoundExpressionTypes.isNumeric(childType) && !BoundExpressionTypes.isNull(childType)) {
            throw new BinderException("Unary minus requires a numeric operand but got "
                    + BoundExpressionTypes.typeName(childType));
        }
        LogicalType resultType = childType.equals(LogicalType.DOUBLE) ? LogicalType.DOUBLE : LogicalType.BIGINT;
        Object zeroValue = resultType.equals(LogicalType.DOUBLE) ? 0.0d : 0L;
        BoundLiteralExpression zero = new BoundLiteralExpression(resultType, zeroValue);
        return new BoundBinaryExpression(zero, BinaryOperator.SUBTRACT, child, resultType);
    }

    private static BoundExpression bindNot(BoundExpression child, LogicalType childType) {
        if (!childType.equals(LogicalType.BOOLEAN) && !BoundExpressionTypes.isNull(childType)) {
            throw new BinderException("NOT requires a BOOLEAN operand but got "
                    + BoundExpressionTypes.typeName(childType));
        }
        BoundLiteralExpression falseLiteral = new BoundLiteralExpression(LogicalType.BOOLEAN, false);
        return new BoundBinaryExpression(child, BinaryOperator.EQUAL, falseLiteral, LogicalType.BOOLEAN);
    }
}
