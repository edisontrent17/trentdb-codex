package dev.trentdb.planner;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.LiteralKind;
import dev.trentdb.types.LogicalType;

import java.util.List;

public final class BoundExpressionTypes {
    private BoundExpressionTypes() {
    }

    public static LogicalType logicalType(BoundExpression expression) {
        return switch (expression) {
            case BoundColumnRefExpression column -> column.logicalType();
            case BoundLiteralExpression literal -> literal.logicalType();
            case BoundFunctionExpression function -> function.logicalType();
            case BoundBinaryExpression binary -> binary.logicalType();
            case BoundAggregateExpression aggregate -> aggregate.logicalType();
            case BoundBetweenExpression between -> between.logicalType();
            case BoundCaseExpression caseExpression -> caseExpression.logicalType();
            case BoundExistsSubqueryExpression exists -> exists.logicalType();
            case BoundInExpression in -> in.logicalType();
            case BoundInSubqueryExpression in -> in.logicalType();
            case BoundCastExpression cast -> cast.logicalType();
            case BoundIntervalExpression ignored -> LogicalType.INTERVAL;
            case BoundOutputColumnExpression output -> output.logicalType();
            case BoundSubqueryExpression subquery -> subquery.logicalType();
        };
    }

    public static LogicalType bindBinaryType(BinaryOperator operator, LogicalType left, LogicalType right) {
        return switch (operator) {
            case EQUAL,
                 NOT_EQUAL,
                 LESS_THAN,
                 LESS_THAN_OR_EQUAL,
                 GREATER_THAN,
                 GREATER_THAN_OR_EQUAL -> bindComparisonType(operator, left, right);
            case LIKE,
                 NOT_LIKE -> bindLikeType(operator, left, right);
            case AND,
                 OR -> bindBooleanType(operator, left, right);
            case ADD,
                 SUBTRACT,
                 MULTIPLY,
                 DIVIDE -> bindArithmeticType(operator, left, right);
        };
    }

    public static boolean canCast(LogicalType source, LogicalType target) {
        if (source.equals(target) || source.equals(LogicalType.NULL)) {
            return true;
        }
        if (target.equals(LogicalType.TEXT)) {
            return true;
        }
        if (isNumeric(source) && isNumeric(target)) {
            return true;
        }
        return source.equals(LogicalType.TEXT) && target.equals(LogicalType.DATE);
    }

    public static LogicalType commonCaseType(List<LogicalType> types) {
        LogicalType result = LogicalType.NULL;
        boolean numeric = false;
        boolean hasDouble = false;
        for (LogicalType type : types) {
            if (isNull(type)) {
                continue;
            }
            if (isNull(result)) {
                result = type;
                numeric = isNumeric(type);
                hasDouble = type.equals(LogicalType.DOUBLE);
                continue;
            }
            if (numeric && isNumeric(type)) {
                hasDouble = hasDouble || type.equals(LogicalType.DOUBLE);
                result = hasDouble ? LogicalType.DOUBLE : LogicalType.BIGINT;
                continue;
            }
            if (!result.equals(type)) {
                throw new BinderException("CASE result types are incompatible: "
                        + typeName(result) + " and " + typeName(type));
            }
        }
        return result;
    }

    public static boolean isComparable(LogicalType left, LogicalType right) {
        if (isNumeric(left) && isNumeric(right)) {
            return true;
        }
        return left.equals(right);
    }

    public static boolean isNull(LogicalType logicalType) {
        return logicalType.equals(LogicalType.NULL);
    }

    public static boolean isNumeric(LogicalType logicalType) {
        return logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DOUBLE);
    }

    public static LogicalType literalType(LiteralKind kind) {
        return switch (kind) {
            case INTEGER -> LogicalType.BIGINT;
            case DECIMAL -> LogicalType.DOUBLE;
            case STRING -> LogicalType.TEXT;
            case BOOLEAN -> LogicalType.BOOLEAN;
            case DATE -> LogicalType.DATE;
            case NULL -> LogicalType.NULL;
        };
    }

    public static String typeName(LogicalType logicalType) {
        return logicalType.id().name();
    }

    private static LogicalType bindComparisonType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if (isNull(left) || isNull(right) || isComparable(left, right)) {
            return LogicalType.BOOLEAN;
        }
        throw new BinderException("Operator " + operator + " cannot compare "
                + typeName(left) + " and " + typeName(right));
    }

    private static LogicalType bindLikeType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if ((left.equals(LogicalType.TEXT) || isNull(left)) && (right.equals(LogicalType.TEXT) || isNull(right))) {
            return LogicalType.BOOLEAN;
        }
        throw new BinderException("Operator " + operator + " requires TEXT operands but got "
                + typeName(left) + " and " + typeName(right));
    }

    private static LogicalType bindBooleanType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if ((left.equals(LogicalType.BOOLEAN) || isNull(left)) && (right.equals(LogicalType.BOOLEAN) || isNull(right))) {
            return LogicalType.BOOLEAN;
        }
        throw new BinderException("Operator " + operator + " requires BOOLEAN operands but got "
                + typeName(left) + " and " + typeName(right));
    }

    private static LogicalType bindArithmeticType(BinaryOperator operator, LogicalType left, LogicalType right) {
        if (operator == BinaryOperator.ADD && isDateInterval(left, right)) {
            return LogicalType.DATE;
        }
        if (operator == BinaryOperator.SUBTRACT && left.equals(LogicalType.DATE) && right.equals(LogicalType.INTERVAL)) {
            return LogicalType.DATE;
        }
        if ((isNumeric(left) || isNull(left)) && (isNumeric(right) || isNull(right))) {
            if (operator == BinaryOperator.DIVIDE || left.equals(LogicalType.DOUBLE) || right.equals(LogicalType.DOUBLE)) {
                return LogicalType.DOUBLE;
            }
            return LogicalType.BIGINT;
        }
        throw new BinderException("Operator " + operator + " requires numeric operands but got "
                + typeName(left) + " and " + typeName(right));
    }

    private static boolean isDateInterval(LogicalType left, LogicalType right) {
        return (left.equals(LogicalType.DATE) && right.equals(LogicalType.INTERVAL))
                || (left.equals(LogicalType.INTERVAL) && right.equals(LogicalType.DATE));
    }
}
