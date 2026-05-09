package dev.trentdb.execution;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.ast.IntervalUnit;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundExistsSubqueryExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

public final class ExpressionExecutor {
    private static final byte SQL_UNKNOWN = -1;
    private static final byte SQL_FALSE = 0;
    private static final byte SQL_TRUE = 1;

    private final SubqueryExpressionEvaluator subqueryEvaluator;
    private final ScalarFunctionExecutor scalarFunctionExecutor;

    public ExpressionExecutor(StorageManager storageManager) {
        this.subqueryEvaluator = new SubqueryExpressionEvaluator(Objects.requireNonNull(storageManager, "storageManager"));
        this.scalarFunctionExecutor = new ScalarFunctionExecutor(this);
    }

    public Vector execute(BoundExpression expression, DataChunk input) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> throw new ExecutionException(
                    "Aggregate expression requires aggregate execution: " + aggregate.name());
            case BoundColumnRefExpression column -> input.column(column.ordinal());
            case BoundOutputColumnExpression output -> input.column(output.ordinal());
            case BoundLiteralExpression literal -> constant(literal, input.cardinality());
            case BoundFunctionExpression function -> scalarFunctionExecutor.execute(function, input);
            case BoundBetweenExpression between -> between(between, input);
            case BoundInExpression in -> in(in, input);
            case BoundInSubqueryExpression in -> subqueryEvaluator.in(in, input, execute(in.input(), input));
            case BoundExistsSubqueryExpression exists -> subqueryEvaluator.exists(exists, input);
            case BoundCastExpression cast -> cast(cast, input);
            case BoundCaseExpression caseExpression -> caseExpression(caseExpression, input);
            case BoundIntervalExpression interval -> throw new ExecutionException(
                    "Standalone INTERVAL literal is not supported yet: " + interval.amount() + " " + interval.unit());
            case BoundSubqueryExpression subquery -> subqueryEvaluator.scalar(subquery, input);
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

    private Vector between(BoundBetweenExpression between, DataChunk input) {
        Vector inputValues = execute(between.input(), input);
        Vector lowerValues = execute(between.lower(), input);
        Vector upperValues = execute(between.upper(), input);
        Vector result = new Vector(between.logicalType(), input.cardinality());

        for (int index = 0; index < input.cardinality(); index++) {
            byte lower = compareNullable(inputValues, lowerValues, index, Comparison.GREATER_THAN_OR_EQUAL);
            byte upper = compareNullable(inputValues, upperValues, index, Comparison.LESS_THAN_OR_EQUAL);
            writeSqlTruth(result, index, sqlAnd(lower, upper));
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
                value = negateSqlTruth(value);
            }
            writeSqlTruth(result, rowIndex, value);
        }
        return result;
    }

    private byte evaluateIn(Vector inputVector, int rowIndex, List<Vector> candidateVectors) {
        if (inputVector.isNull(rowIndex)) {
            return SQL_UNKNOWN;
        }
        boolean hasNullCandidate = false;
        for (Vector candidateVector : candidateVectors) {
            if (candidateVector.isNull(rowIndex)) {
                hasNullCandidate = true;
                continue;
            }
            if (compareValues(inputVector, rowIndex, candidateVector, rowIndex) == 0) {
                return SQL_TRUE;
            }
        }
        return hasNullCandidate ? SQL_UNKNOWN : SQL_FALSE;
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

    private Vector caseExpression(BoundCaseExpression caseExpression, DataChunk input) {
        List<Vector> conditionVectors = caseExpression.branches().stream()
                .map(branch -> execute(branch.condition(), input))
                .toList();
        List<Vector> resultVectors = caseExpression.branches().stream()
                .map(branch -> execute(branch.result(), input))
                .toList();
        Vector elseVector = execute(caseExpression.elseExpression(), input);
        Vector result = new Vector(caseExpression.logicalType(), input.cardinality());

        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            boolean matched = false;
            for (int branchIndex = 0; branchIndex < conditionVectors.size(); branchIndex++) {
                Vector conditionVector = conditionVectors.get(branchIndex);
                if (conditionVector.isNull(rowIndex)) {
                    continue;
                }
                if (!conditionVector.logicalType().equals(LogicalType.BOOLEAN)) {
                    throw new ExecutionException("CASE WHEN condition did not evaluate to BOOLEAN");
                }
                if (conditionVector.getBoolean(rowIndex)) {
                    writeCastValue(result, rowIndex, resultVectors.get(branchIndex), rowIndex);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                writeCastValue(result, rowIndex, elseVector, rowIndex);
            }
        }
        return result;
    }

    private void writeCastValue(Vector target, int targetIndex, Vector source, int sourceIndex) {
        if (source.isNull(sourceIndex)) {
            target.setNull(targetIndex);
            return;
        }
        if (target.logicalType().equals(source.logicalType())) {
            target.copyFrom(targetIndex, source, sourceIndex);
            return;
        }
        if (target.logicalType().equals(LogicalType.DOUBLE) && isNumeric(source.logicalType())) {
            target.setDouble(targetIndex, numericAsDouble(source, sourceIndex));
            return;
        }
        if (target.logicalType().equals(LogicalType.BIGINT) && source.logicalType().equals(LogicalType.INTEGER)) {
            target.setBigint(targetIndex, source.getInteger(sourceIndex));
            return;
        }
        throw new ExecutionException("Cannot write CASE value " + source.logicalType().id().name()
                + " to " + target.logicalType().id().name());
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
        if (isDateIntervalArithmetic(binary)) {
            return dateIntervalArithmetic(binary, input);
        }
        Vector left = execute(binary.left(), input);
        Vector right = execute(binary.right(), input);
        Vector result = new Vector(binary.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            writeBinaryValue(binary, left, right, index, result);
        }
        return result;
    }

    private boolean isDateIntervalArithmetic(BoundBinaryExpression binary) {
        if (binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUBTRACT) {
            return false;
        }
        if (binary.left() instanceof BoundIntervalExpression) {
            return binary.operator() == BinaryOperator.ADD && !(binary.right() instanceof BoundIntervalExpression);
        }
        return binary.right() instanceof BoundIntervalExpression;
    }

    private Vector dateIntervalArithmetic(BoundBinaryExpression binary, DataChunk input) {
        BoundIntervalExpression interval;
        BoundExpression dateExpression;
        int sign = 1;
        if (binary.left() instanceof BoundIntervalExpression leftInterval) {
            interval = leftInterval;
            dateExpression = binary.right();
        } else if (binary.right() instanceof BoundIntervalExpression rightInterval) {
            interval = rightInterval;
            dateExpression = binary.left();
            if (binary.operator() == BinaryOperator.SUBTRACT) {
                sign = -1;
            }
        } else {
            throw new ExecutionException("DATE arithmetic requires one INTERVAL operand");
        }
        Vector dates = execute(dateExpression, input);
        if (!dates.logicalType().equals(LogicalType.DATE)) {
            throw new ExecutionException("DATE arithmetic requires one DATE operand");
        }
        Vector result = new Vector(LogicalType.DATE, input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            if (dates.isNull(index)) {
                result.setNull(index);
                continue;
            }
            result.setDate(index, addInterval(dates.getDate(index), interval, sign));
        }
        return result;
    }

    private LocalDate addInterval(LocalDate date, BoundIntervalExpression interval, int sign) {
        long amount = interval.amount() * sign;
        IntervalUnit unit = interval.unit();
        return switch (unit) {
            case DAY -> date.plusDays(amount);
            case MONTH -> date.plusMonths(amount);
            case YEAR -> date.plusYears(amount);
        };
    }

    private void writeBinaryValue(BoundBinaryExpression binary, Vector left, Vector right, int index, Vector result) {
        switch (binary.operator()) {
            case EQUAL -> writeSqlTruth(result, index, compareNullable(left, right, index, Comparison.EQUAL));
            case NOT_EQUAL -> writeSqlTruth(result, index, negateSqlTruth(compareNullable(left, right, index, Comparison.EQUAL)));
            case LESS_THAN -> writeSqlTruth(result, index, compareNullable(left, right, index, Comparison.LESS_THAN));
            case LESS_THAN_OR_EQUAL ->
                    writeSqlTruth(result, index, compareNullable(left, right, index, Comparison.LESS_THAN_OR_EQUAL));
            case GREATER_THAN ->
                    writeSqlTruth(result, index, compareNullable(left, right, index, Comparison.GREATER_THAN));
            case GREATER_THAN_OR_EQUAL ->
                    writeSqlTruth(result, index, compareNullable(left, right, index, Comparison.GREATER_THAN_OR_EQUAL));
            case LIKE -> writeSqlTruth(result, index, likeNullable(left, right, index, false));
            case NOT_LIKE -> writeSqlTruth(result, index, likeNullable(left, right, index, true));
            case AND -> writeSqlTruth(result, index, sqlAnd(readSqlTruth(left, index), readSqlTruth(right, index)));
            case OR -> writeSqlTruth(result, index, sqlOr(readSqlTruth(left, index), readSqlTruth(right, index)));
            case ADD, SUBTRACT, MULTIPLY, DIVIDE -> writeArithmetic(binary.operator(), binary.logicalType(), left, right, index, result);
        }
    }

    private byte likeNullable(Vector left, Vector right, int index, boolean negated) {
        if (left.isNull(index) || right.isNull(index)) {
            return SQL_UNKNOWN;
        }
        if (!left.logicalType().equals(LogicalType.TEXT) || !right.logicalType().equals(LogicalType.TEXT)) {
            throw new ExecutionException("LIKE expects TEXT operands");
        }
        boolean matched = likeMatches(left.getText(index), right.getText(index));
        if (negated) {
            matched = !matched;
        }
        return matched ? SQL_TRUE : SQL_FALSE;
    }

    private boolean likeMatches(String text, String pattern) {
        int textIndex = 0;
        int patternIndex = 0;
        int starIndex = -1;
        int starTextIndex = 0;
        while (textIndex < text.length()) {
            if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == '_' || pattern.charAt(patternIndex) == text.charAt(textIndex))) {
                textIndex++;
                patternIndex++;
                continue;
            }
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '%') {
                starIndex = patternIndex;
                patternIndex++;
                starTextIndex = textIndex;
                continue;
            }
            if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                starTextIndex++;
                textIndex = starTextIndex;
                continue;
            }
            return false;
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '%') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
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

    private byte compareNullable(Vector left, Vector right, int index, Comparison comparison) {
        if (left.isNull(index) || right.isNull(index)) {
            return SQL_UNKNOWN;
        }
        int value = compareValues(left, index, right, index);
        return switch (comparison) {
            case EQUAL -> value == 0 ? SQL_TRUE : SQL_FALSE;
            case LESS_THAN -> value < 0 ? SQL_TRUE : SQL_FALSE;
            case LESS_THAN_OR_EQUAL -> value <= 0 ? SQL_TRUE : SQL_FALSE;
            case GREATER_THAN -> value > 0 ? SQL_TRUE : SQL_FALSE;
            case GREATER_THAN_OR_EQUAL -> value >= 0 ? SQL_TRUE : SQL_FALSE;
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

    private byte readSqlTruth(Vector vector, int index) {
        if (vector.isNull(index)) {
            return SQL_UNKNOWN;
        }
        if (!vector.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Predicate did not evaluate to BOOLEAN");
        }
        return vector.getBoolean(index) ? SQL_TRUE : SQL_FALSE;
    }

    private void writeSqlTruth(Vector result, int index, byte value) {
        if (value == SQL_UNKNOWN) {
            result.setNull(index);
            return;
        }
        result.setBoolean(index, value == SQL_TRUE);
    }

    private byte negateSqlTruth(byte value) {
        if (value == SQL_UNKNOWN) {
            return SQL_UNKNOWN;
        }
        return value == SQL_TRUE ? SQL_FALSE : SQL_TRUE;
    }

    private byte sqlAnd(byte left, byte right) {
        if (left == SQL_FALSE || right == SQL_FALSE) {
            return SQL_FALSE;
        }
        if (left == SQL_UNKNOWN || right == SQL_UNKNOWN) {
            return SQL_UNKNOWN;
        }
        return SQL_TRUE;
    }

    private byte sqlOr(byte left, byte right) {
        if (left == SQL_TRUE || right == SQL_TRUE) {
            return SQL_TRUE;
        }
        if (left == SQL_UNKNOWN || right == SQL_UNKNOWN) {
            return SQL_UNKNOWN;
        }
        return SQL_FALSE;
    }

    private enum Comparison {
        EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL
    }
}
