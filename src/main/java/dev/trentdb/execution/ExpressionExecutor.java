package dev.trentdb.execution;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class ExpressionExecutor {
    public Vector execute(BoundExpression expression, DataChunk input) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> throw new ExecutionException(
                    "Aggregate expression requires aggregate execution: " + aggregate.name());
            case BoundColumnRefExpression column -> input.column(column.ordinal());
            case BoundLiteralExpression literal -> constant(literal.logicalType(), literal.value(), input.cardinality());
            case BoundFunctionExpression function -> function(function, input);
            case BoundBetweenExpression between -> between(between, input);
            case BoundCastExpression cast -> cast(cast, input);
            case BoundBinaryExpression binary -> binary(binary, input);
        };
    }

    private Vector constant(LogicalType logicalType, Object value, int cardinality) {
        return Vector.constant(logicalType, value, cardinality);
    }

    private Vector function(BoundFunctionExpression function, DataChunk input) {
        if (function.name().equalsIgnoreCase("lower")) {
            Vector argument = execute(function.arguments().getFirst(), input);
            Vector result = new Vector(function.logicalType(), input.cardinality());
            for (int index = 0; index < input.cardinality(); index++) {
                if (argument.isNull(index)) {
                    result.setNull(index);
                } else {
                    Object value = argument.get(index);
                    if (value instanceof String text) {
                        result.set(index, text.toLowerCase(Locale.ROOT));
                    } else {
                        throw new ExecutionException("lower expects TEXT input");
                    }
                }
            }
            return result;
        }
        throw new ExecutionException("Unsupported scalar function: " + function.name());
    }

    private Vector between(BoundBetweenExpression between, DataChunk input) {
        Vector inputValues = execute(between.input(), input);
        Vector lowerValues = execute(between.lower(), input);
        Vector upperValues = execute(between.upper(), input);
        Vector result = new Vector(between.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            Object lowerComparison = compareNullableLess(
                    inputValues.get(index),
                    lowerValues.get(index),
                    Comparison.GREATER_THAN_OR_EQUAL
            );
            Object upperComparison = compareNullableLess(
                    inputValues.get(index),
                    upperValues.get(index),
                    Comparison.LESS_THAN_OR_EQUAL
            );
            result.set(index, and(lowerComparison, upperComparison));
        }
        return result;
    }

    private Vector cast(BoundCastExpression cast, DataChunk input) {
        Vector child = execute(cast.child(), input);
        LogicalType targetType = cast.logicalType();
        if (targetType.equals(LogicalType.TEXT)) {
            return castToText(child, input.cardinality());
        }
        if (targetType.equals(LogicalType.DATE)) {
            return castToDate(child, input.cardinality());
        }
        if (targetType.equals(LogicalType.INTEGER)) {
            return castToInteger(child, input.cardinality());
        }
        if (targetType.equals(LogicalType.BIGINT)) {
            return castToBigint(child, input.cardinality());
        }
        if (targetType.equals(LogicalType.DOUBLE)) {
            return castToDouble(child, input.cardinality());
        }
        if (targetType.equals(LogicalType.BOOLEAN)) {
            return castToBoolean(child, input.cardinality());
        }
        throw new ExecutionException("Unsupported cast to " + targetType.id());
    }

    private Vector castToText(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.TEXT, cardinality);
        for (int index = 0; index < cardinality; index++) {
            Object value = child.get(index);
            result.set(index, value == null ? null : value.toString());
        }
        return result;
    }

    private Vector castToDate(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.DATE, cardinality);
        for (int index = 0; index < cardinality; index++) {
            Object value = child.get(index);
            result.set(index, value == null ? null : castObjectToDate(value));
        }
        return result;
    }

    private Vector castToInteger(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.INTEGER, cardinality);
        for (int index = 0; index < cardinality; index++) {
            Object value = child.get(index);
            result.set(index, value == null ? null : castObjectToNumber(value).intValue());
        }
        return result;
    }

    private Vector castToBigint(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.BIGINT, cardinality);
        for (int index = 0; index < cardinality; index++) {
            Object value = child.get(index);
            result.set(index, value == null ? null : castObjectToNumber(value).longValue());
        }
        return result;
    }

    private Vector castToDouble(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.DOUBLE, cardinality);
        for (int index = 0; index < cardinality; index++) {
            Object value = child.get(index);
            result.set(index, value == null ? null : castObjectToNumber(value).doubleValue());
        }
        return result;
    }

    private Vector castToBoolean(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.BOOLEAN, cardinality);
        for (int index = 0; index < cardinality; index++) {
            Object value = child.get(index);
            if (value == null || value instanceof Boolean) {
                result.set(index, value);
            } else {
                throw new ExecutionException("BOOLEAN cast expects BOOLEAN input");
            }
        }
        return result;
    }

    private Number castObjectToNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        throw new ExecutionException("Numeric cast expects numeric input");
    }

    private LocalDate castObjectToDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException exception) {
                throw new ExecutionException("Could not cast value to DATE: " + text);
            }
        }
        throw new ExecutionException("DATE cast expects TEXT input");
    }

    private Vector binary(BoundBinaryExpression binary, DataChunk input) {
        Vector left = execute(binary.left(), input);
        Vector right = execute(binary.right(), input);
        Vector result = new Vector(binary.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            result.set(index, evaluateBinary(binary, left.get(index), right.get(index)));
        }
        return result;
    }

    private Object evaluateBinary(BoundBinaryExpression binary, Object left, Object right) {
        return switch (binary.operator()) {
            case EQUAL -> compareNullable(left, right, 0);
            case NOT_EQUAL -> negate(compareNullable(left, right, 0));
            case LESS_THAN -> compareNullableLess(left, right, Comparison.LESS_THAN);
            case LESS_THAN_OR_EQUAL -> compareNullableLess(left, right, Comparison.LESS_THAN_OR_EQUAL);
            case GREATER_THAN -> compareNullableLess(left, right, Comparison.GREATER_THAN);
            case GREATER_THAN_OR_EQUAL -> compareNullableLess(left, right, Comparison.GREATER_THAN_OR_EQUAL);
            case AND -> and(left, right);
            case OR -> or(left, right);
            case ADD, SUBTRACT, MULTIPLY, DIVIDE -> arithmetic(binary.operator(), binary.logicalType(), left, right);
        };
    }

    private Object arithmetic(BinaryOperator operator, LogicalType resultType, Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) {
            throw new ExecutionException("Arithmetic operator " + operator + " expects numeric input");
        }
        if (resultType.equals(LogicalType.DOUBLE)) {
            return doubleArithmetic(operator, leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return longArithmetic(operator, leftNumber.longValue(), rightNumber.longValue());
    }

    private Object doubleArithmetic(BinaryOperator operator, double left, double right) {
        if (operator == BinaryOperator.DIVIDE && right == 0.0d) {
            throw new ExecutionException("Division by zero");
        }
        return switch (operator) {
            case ADD -> left + right;
            case SUBTRACT -> left - right;
            case MULTIPLY -> left * right;
            case DIVIDE -> left / right;
            default -> throw new ExecutionException("Unsupported arithmetic operator: " + operator);
        };
    }

    private Object longArithmetic(BinaryOperator operator, long left, long right) {
        return switch (operator) {
            case ADD -> left + right;
            case SUBTRACT -> left - right;
            case MULTIPLY -> left * right;
            default -> throw new ExecutionException("Unsupported arithmetic operator: " + operator);
        };
    }

    private Object compareNullable(Object left, Object right, int expected) {
        if (left == null || right == null) {
            return null;
        }
        return compare(left, right) == expected;
    }

    private Object compareNullableLess(Object left, Object right, Comparison comparison) {
        if (left == null || right == null) {
            return null;
        }
        int result = compare(left, right);
        return switch (comparison) {
            case LESS_THAN -> result < 0;
            case LESS_THAN_OR_EQUAL -> result <= 0;
            case GREATER_THAN -> result > 0;
            case GREATER_THAN_OR_EQUAL -> result >= 0;
        };
    }

    private Object negate(Object value) {
        return value == null ? null : !((Boolean) value);
    }

    private Object and(Object left, Object right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
            return false;
        }
        if (left == null || right == null) {
            return null;
        }
        return true;
    }

    private Object or(Object left, Object right) {
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
            return true;
        }
        if (left == null || right == null) {
            return null;
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compare(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        throw new ExecutionException("Cannot compare " + left.getClass().getSimpleName()
                + " and " + right.getClass().getSimpleName());
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        throw new ExecutionException("Predicate did not evaluate to BOOLEAN");
    }

    private enum Comparison {
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL
    }
}
