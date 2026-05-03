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
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public final class ExpressionExecutor {
    private static final byte TRI_NULL = -1;
    private static final byte TRI_FALSE = 0;
    private static final byte TRI_TRUE = 1;

    public Vector execute(BoundExpression expression, DataChunk input) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> throw new ExecutionException(
                    "Aggregate expression requires aggregate execution: " + aggregate.name());
            case BoundColumnRefExpression column -> input.column(column.ordinal());
            case BoundOutputColumnExpression output -> input.column(output.ordinal());
            case BoundLiteralExpression literal -> constant(literal, input.cardinality());
            case BoundFunctionExpression function -> function(function, input);
            case BoundBetweenExpression between -> between(between, input);
            case BoundInExpression in -> in(in, input);
            case BoundCastExpression cast -> cast(cast, input);
            case BoundBinaryExpression binary -> binary(binary, input);
        };
    }

    private Vector constant(BoundLiteralExpression literal, int cardinality) {
        LogicalType logicalType = literal.logicalType();
        Object value = literal.value();
        if (logicalType.equals(LogicalType.NULL)) {
            return Vector.constantNull(LogicalType.NULL, cardinality);
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            return Vector.constantBoolean((Boolean) value, cardinality);
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            Integer integerValue = value == null ? null : ((Number) value).intValue();
            return Vector.constantInteger(integerValue, cardinality);
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            Long bigintValue = value == null ? null : ((Number) value).longValue();
            return Vector.constantBigint(bigintValue, cardinality);
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            Double doubleValue = value == null ? null : ((Number) value).doubleValue();
            return Vector.constantDouble(doubleValue, cardinality);
        }
        if (logicalType.equals(LogicalType.TEXT)) {
            return Vector.constantText((String) value, cardinality);
        }
        if (logicalType.equals(LogicalType.DATE)) {
            return Vector.constantDate((LocalDate) value, cardinality);
        }
        throw new ExecutionException("Unsupported literal type: " + logicalType.id());
    }

    private Vector function(BoundFunctionExpression function, DataChunk input) {
        if (!function.name().equalsIgnoreCase("lower")) {
            throw new ExecutionException("Unsupported scalar function: " + function.name());
        }
        Vector argument = execute(function.arguments().getFirst(), input);
        if (!argument.logicalType().equals(LogicalType.TEXT)) {
            throw new ExecutionException("lower expects TEXT input");
        }
        Vector result = new Vector(function.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            if (argument.isNull(index)) {
                result.setNull(index);
                continue;
            }
            result.setText(index, argument.getText(index).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private Vector between(BoundBetweenExpression between, DataChunk input) {
        Vector inputValues = execute(between.input(), input);
        Vector lowerValues = execute(between.lower(), input);
        Vector upperValues = execute(between.upper(), input);
        Vector result = new Vector(between.logicalType(), input.cardinality());

        for (int index = 0; index < input.cardinality(); index++) {
            byte lower = compareNullable(inputValues, lowerValues, index, Comparison.GREATER_THAN_OR_EQUAL);
            byte upper = compareNullable(inputValues, upperValues, index, Comparison.LESS_THAN_OR_EQUAL);
            writeTriState(result, index, triAnd(lower, upper));
        }
        return result;
    }

    private Vector in(BoundInExpression in, DataChunk input) {
        Vector inputValues = execute(in.input(), input);
        Vector result = new Vector(in.logicalType(), input.cardinality());
        List<Vector> candidateVectors = in.candidates().stream()
                .map(candidate -> execute(candidate, input))
                .toList();

        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            byte value = evaluateIn(inputValues, rowIndex, candidateVectors);
            if (in.negated()) {
                value = triNegate(value);
            }
            writeTriState(result, rowIndex, value);
        }
        return result;
    }

    private byte evaluateIn(Vector inputVector, int rowIndex, List<Vector> candidateVectors) {
        if (inputVector.isNull(rowIndex)) {
            return TRI_NULL;
        }
        boolean hasNullCandidate = false;
        for (Vector candidateVector : candidateVectors) {
            if (candidateVector.isNull(rowIndex)) {
                hasNullCandidate = true;
                continue;
            }
            if (compareValues(inputVector, rowIndex, candidateVector, rowIndex) == 0) {
                return TRI_TRUE;
            }
        }
        return hasNullCandidate ? TRI_NULL : TRI_FALSE;
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
            if (child.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (child.logicalType().equals(LogicalType.BOOLEAN)) {
                result.setText(index, Boolean.toString(child.getBoolean(index)));
                continue;
            }
            if (child.logicalType().equals(LogicalType.INTEGER)) {
                result.setText(index, Integer.toString(child.getInteger(index)));
                continue;
            }
            if (child.logicalType().equals(LogicalType.BIGINT)) {
                result.setText(index, Long.toString(child.getBigint(index)));
                continue;
            }
            if (child.logicalType().equals(LogicalType.DOUBLE)) {
                result.setText(index, Double.toString(child.getDouble(index)));
                continue;
            }
            if (child.logicalType().equals(LogicalType.TEXT)) {
                result.setText(index, child.getText(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.DATE)) {
                result.setText(index, child.getDate(index).toString());
                continue;
            }
            throw new ExecutionException("Cannot cast " + child.logicalType().id() + " to TEXT");
        }
        return result;
    }

    private Vector castToDate(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.DATE, cardinality);
        for (int index = 0; index < cardinality; index++) {
            if (child.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (child.logicalType().equals(LogicalType.DATE)) {
                result.setDate(index, child.getDate(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.TEXT)) {
                String text = child.getText(index);
                try {
                    result.setDate(index, LocalDate.parse(text));
                } catch (DateTimeParseException exception) {
                    throw new ExecutionException("Could not cast value to DATE: " + text);
                }
                continue;
            }
            throw new ExecutionException("DATE cast expects TEXT input");
        }
        return result;
    }

    private Vector castToInteger(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.INTEGER, cardinality);
        for (int index = 0; index < cardinality; index++) {
            if (child.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (child.logicalType().equals(LogicalType.INTEGER)) {
                result.setInteger(index, child.getInteger(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.BIGINT)) {
                result.setInteger(index, (int) child.getBigint(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.DOUBLE)) {
                result.setInteger(index, (int) child.getDouble(index));
                continue;
            }
            throw new ExecutionException("Numeric cast expects numeric input");
        }
        return result;
    }

    private Vector castToBigint(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.BIGINT, cardinality);
        for (int index = 0; index < cardinality; index++) {
            if (child.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (child.logicalType().equals(LogicalType.INTEGER)) {
                result.setBigint(index, child.getInteger(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.BIGINT)) {
                result.setBigint(index, child.getBigint(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.DOUBLE)) {
                result.setBigint(index, (long) child.getDouble(index));
                continue;
            }
            throw new ExecutionException("Numeric cast expects numeric input");
        }
        return result;
    }

    private Vector castToDouble(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.DOUBLE, cardinality);
        for (int index = 0; index < cardinality; index++) {
            if (child.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (child.logicalType().equals(LogicalType.INTEGER)) {
                result.setDouble(index, child.getInteger(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.BIGINT)) {
                result.setDouble(index, child.getBigint(index));
                continue;
            }
            if (child.logicalType().equals(LogicalType.DOUBLE)) {
                result.setDouble(index, child.getDouble(index));
                continue;
            }
            throw new ExecutionException("Numeric cast expects numeric input");
        }
        return result;
    }

    private Vector castToBoolean(Vector child, int cardinality) {
        Vector result = new Vector(LogicalType.BOOLEAN, cardinality);
        for (int index = 0; index < cardinality; index++) {
            if (child.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (!child.logicalType().equals(LogicalType.BOOLEAN)) {
                throw new ExecutionException("BOOLEAN cast expects BOOLEAN input");
            }
            result.setBoolean(index, child.getBoolean(index));
        }
        return result;
    }

    private Vector binary(BoundBinaryExpression binary, DataChunk input) {
        Vector left = execute(binary.left(), input);
        Vector right = execute(binary.right(), input);
        Vector result = new Vector(binary.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            writeBinaryValue(binary, left, right, index, result);
        }
        return result;
    }

    private void writeBinaryValue(BoundBinaryExpression binary, Vector left, Vector right, int index, Vector result) {
        switch (binary.operator()) {
            case EQUAL -> writeTriState(result, index, compareNullable(left, right, index, Comparison.EQUAL));
            case NOT_EQUAL -> writeTriState(result, index, triNegate(compareNullable(left, right, index, Comparison.EQUAL)));
            case LESS_THAN -> writeTriState(result, index, compareNullable(left, right, index, Comparison.LESS_THAN));
            case LESS_THAN_OR_EQUAL ->
                    writeTriState(result, index, compareNullable(left, right, index, Comparison.LESS_THAN_OR_EQUAL));
            case GREATER_THAN ->
                    writeTriState(result, index, compareNullable(left, right, index, Comparison.GREATER_THAN));
            case GREATER_THAN_OR_EQUAL ->
                    writeTriState(result, index, compareNullable(left, right, index, Comparison.GREATER_THAN_OR_EQUAL));
            case AND -> writeTriState(result, index, triAnd(readTriState(left, index), readTriState(right, index)));
            case OR -> writeTriState(result, index, triOr(readTriState(left, index), readTriState(right, index)));
            case ADD, SUBTRACT, MULTIPLY, DIVIDE -> writeArithmetic(binary.operator(), binary.logicalType(), left, right, index, result);
        }
    }

    private void writeArithmetic(
            BinaryOperator operator,
            LogicalType resultType,
            Vector left,
            Vector right,
            int index,
            Vector result
    ) {
        if (left.isNull(index) || right.isNull(index)) {
            result.setNull(index);
            return;
        }
        if (resultType.equals(LogicalType.DATE)) {
            LocalDate value = dateArithmetic(operator, left, right, index);
            result.setDate(index, value);
            return;
        }
        if (resultType.equals(LogicalType.DOUBLE)) {
            double leftValue = numericAsDouble(left, index);
            double rightValue = numericAsDouble(right, index);
            if (operator == BinaryOperator.DIVIDE && rightValue == 0.0d) {
                throw new ExecutionException("Division by zero");
            }
            result.setDouble(index, switch (operator) {
                case ADD -> leftValue + rightValue;
                case SUBTRACT -> leftValue - rightValue;
                case MULTIPLY -> leftValue * rightValue;
                case DIVIDE -> leftValue / rightValue;
                default -> throw new ExecutionException("Unsupported arithmetic operator: " + operator);
            });
            return;
        }

        long leftValue = numericAsLong(left, index);
        long rightValue = numericAsLong(right, index);
        result.setBigint(index, switch (operator) {
            case ADD -> leftValue + rightValue;
            case SUBTRACT -> leftValue - rightValue;
            case MULTIPLY -> leftValue * rightValue;
            default -> throw new ExecutionException("Unsupported arithmetic operator: " + operator);
        });
    }

    private LocalDate dateArithmetic(BinaryOperator operator, Vector left, Vector right, int index) {
        LogicalType leftType = left.logicalType();
        LogicalType rightType = right.logicalType();
        if (operator == BinaryOperator.ADD) {
            if (leftType.equals(LogicalType.DATE) && rightType.equals(LogicalType.BIGINT)) {
                return left.getDate(index).plusDays(right.getBigint(index));
            }
            if (leftType.equals(LogicalType.BIGINT) && rightType.equals(LogicalType.DATE)) {
                return right.getDate(index).plusDays(left.getBigint(index));
            }
        }
        if (operator == BinaryOperator.SUBTRACT
                && leftType.equals(LogicalType.DATE)
                && rightType.equals(LogicalType.BIGINT)) {
            return left.getDate(index).minusDays(right.getBigint(index));
        }
        throw new ExecutionException("Unsupported DATE arithmetic for operator " + operator);
    }

    private byte compareNullable(Vector left, Vector right, int index, Comparison comparison) {
        if (left.isNull(index) || right.isNull(index)) {
            return TRI_NULL;
        }
        int value = compareValues(left, index, right, index);
        return switch (comparison) {
            case EQUAL -> value == 0 ? TRI_TRUE : TRI_FALSE;
            case LESS_THAN -> value < 0 ? TRI_TRUE : TRI_FALSE;
            case LESS_THAN_OR_EQUAL -> value <= 0 ? TRI_TRUE : TRI_FALSE;
            case GREATER_THAN -> value > 0 ? TRI_TRUE : TRI_FALSE;
            case GREATER_THAN_OR_EQUAL -> value >= 0 ? TRI_TRUE : TRI_FALSE;
        };
    }

    private int compareValues(Vector left, int leftIndex, Vector right, int rightIndex) {
        LogicalType leftType = left.logicalType();
        LogicalType rightType = right.logicalType();

        if (isNumeric(leftType) && isNumeric(rightType)) {
            return Double.compare(numericAsDouble(left, leftIndex), numericAsDouble(right, rightIndex));
        }
        if (leftType.equals(LogicalType.BOOLEAN) && rightType.equals(LogicalType.BOOLEAN)) {
            return Boolean.compare(left.getBoolean(leftIndex), right.getBoolean(rightIndex));
        }
        if (leftType.equals(LogicalType.TEXT) && rightType.equals(LogicalType.TEXT)) {
            return left.getText(leftIndex).compareTo(right.getText(rightIndex));
        }
        if (leftType.equals(LogicalType.DATE) && rightType.equals(LogicalType.DATE)) {
            return left.getDate(leftIndex).compareTo(right.getDate(rightIndex));
        }
        throw new ExecutionException("Cannot compare " + leftType.id().name() + " and " + rightType.id().name());
    }

    private boolean isNumeric(LogicalType type) {
        return type.equals(LogicalType.INTEGER)
                || type.equals(LogicalType.BIGINT)
                || type.equals(LogicalType.DOUBLE);
    }

    private double numericAsDouble(Vector vector, int index) {
        LogicalType type = vector.logicalType();
        if (type.equals(LogicalType.INTEGER)) {
            return vector.getInteger(index);
        }
        if (type.equals(LogicalType.BIGINT)) {
            return vector.getBigint(index);
        }
        if (type.equals(LogicalType.DOUBLE)) {
            return vector.getDouble(index);
        }
        throw new ExecutionException("Expected numeric value but got " + type.id().name());
    }

    private long numericAsLong(Vector vector, int index) {
        LogicalType type = vector.logicalType();
        if (type.equals(LogicalType.INTEGER)) {
            return vector.getInteger(index);
        }
        if (type.equals(LogicalType.BIGINT)) {
            return vector.getBigint(index);
        }
        if (type.equals(LogicalType.DOUBLE)) {
            return (long) vector.getDouble(index);
        }
        throw new ExecutionException("Expected numeric value but got " + type.id().name());
    }

    private byte readTriState(Vector vector, int index) {
        if (vector.isNull(index)) {
            return TRI_NULL;
        }
        if (!vector.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Predicate did not evaluate to BOOLEAN");
        }
        return vector.getBoolean(index) ? TRI_TRUE : TRI_FALSE;
    }

    private void writeTriState(Vector result, int index, byte value) {
        if (value == TRI_NULL) {
            result.setNull(index);
            return;
        }
        result.setBoolean(index, value == TRI_TRUE);
    }

    private byte triNegate(byte value) {
        if (value == TRI_NULL) {
            return TRI_NULL;
        }
        return value == TRI_TRUE ? TRI_FALSE : TRI_TRUE;
    }

    private byte triAnd(byte left, byte right) {
        if (left == TRI_FALSE || right == TRI_FALSE) {
            return TRI_FALSE;
        }
        if (left == TRI_NULL || right == TRI_NULL) {
            return TRI_NULL;
        }
        return TRI_TRUE;
    }

    private byte triOr(byte left, byte right) {
        if (left == TRI_TRUE || right == TRI_TRUE) {
            return TRI_TRUE;
        }
        if (left == TRI_NULL || right == TRI_NULL) {
            return TRI_NULL;
        }
        return TRI_FALSE;
    }

    private enum Comparison {
        EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL
    }
}
