package dev.trentdb.execution;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.types.LogicalType;

import java.util.Locale;

public final class ExpressionExecutor {
    public Vector execute(BoundExpression expression, DataChunk input) {
        return switch (expression) {
            case BoundColumnRefExpression column -> input.column(column.ordinal());
            case BoundLiteralExpression literal -> constant(literal.logicalType(), literal.value(), input.cardinality());
            case BoundFunctionExpression function -> function(function, input);
            case BoundBinaryExpression binary -> binary(binary, input);
        };
    }

    private Vector constant(LogicalType logicalType, Object value, int cardinality) {
        return Vector.constant(logicalType, value, cardinality);
    }

    private Vector function(BoundFunctionExpression function, DataChunk input) {
        if (function.name().equalsIgnoreCase("lower")) {
            var argument = execute(function.arguments().getFirst(), input);
            var result = new Vector(function.logicalType(), input.cardinality());
            for (int index = 0; index < input.cardinality(); index++) {
                if (argument.isNull(index)) {
                    result.setNull(index);
                } else {
                    var value = argument.get(index);
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

    private Vector binary(BoundBinaryExpression binary, DataChunk input) {
        var left = execute(binary.left(), input);
        var right = execute(binary.right(), input);
        var result = new Vector(binary.logicalType(), input.cardinality());
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
        var result = compare(left, right);
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
