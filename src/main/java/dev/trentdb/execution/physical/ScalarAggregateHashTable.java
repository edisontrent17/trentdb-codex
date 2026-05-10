package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

final class ScalarAggregateHashTable {
    private final int keyCount;
    private final long[] firstKeys;
    private final long[] secondKeys;
    private final boolean[] occupied;
    private final AggregateState[] states;
    private final ScalarAggregateValue[] results;
    private final int mask;
    private int size;

    ScalarAggregateHashTable(int keyCount, int rowCapacity) {
        this.keyCount = keyCount;
        int bucketCount = nextPowerOfTwo(Math.max(16, Math.max(1, rowCapacity) * 2));
        this.firstKeys = new long[bucketCount];
        this.secondKeys = new long[bucketCount];
        this.occupied = new boolean[bucketCount];
        this.states = new AggregateState[bucketCount];
        this.results = new ScalarAggregateValue[bucketCount];
        this.mask = bucketCount - 1;
    }

    void update(
            long first,
            long second,
            BoundAggregateExpression aggregate,
            Vector inputVector,
            int rowIndex,
            boolean starArgument
    ) {
        getOrCreate(first, second, aggregate).update(inputVector, rowIndex, starArgument);
    }

    void finish(
            BoundAggregateExpression aggregate,
            BoundExpression outputExpression,
            LogicalType outputType,
            ExpressionExecutor expressionExecutor
    ) {
        for (int index = 0; index < occupied.length; index++) {
            if (!occupied[index]) {
                continue;
            }
            results[index] = finishState(states[index], aggregate, outputExpression, outputType, expressionExecutor);
        }
    }

    static ScalarAggregateValue emptyResult(
            BoundAggregateExpression aggregate,
            BoundExpression outputExpression,
            LogicalType outputType,
            ExpressionExecutor expressionExecutor
    ) {
        AggregateState state = new AggregateState(aggregate);
        return finishState(state, aggregate, outputExpression, outputType, expressionExecutor);
    }

    ScalarAggregateValue result(long first, long second) {
        int bucket = bucket(first, second);
        while (occupied[bucket]) {
            if (firstKeys[bucket] == first && (keyCount == 1 || secondKeys[bucket] == second)) {
                return results[bucket];
            }
            bucket = (bucket + 1) & mask;
        }
        return null;
    }

    int size() {
        return size;
    }

    private AggregateState getOrCreate(long first, long second, BoundAggregateExpression aggregate) {
        int bucket = bucket(first, second);
        while (occupied[bucket]) {
            if (firstKeys[bucket] == first && (keyCount == 1 || secondKeys[bucket] == second)) {
                return states[bucket];
            }
            bucket = (bucket + 1) & mask;
        }
        occupied[bucket] = true;
        firstKeys[bucket] = first;
        secondKeys[bucket] = second;
        states[bucket] = new AggregateState(aggregate);
        size++;
        return states[bucket];
    }

    private static ScalarAggregateValue finishState(
            AggregateState state,
            BoundAggregateExpression aggregate,
            BoundExpression outputExpression,
            LogicalType outputType,
            ExpressionExecutor expressionExecutor
    ) {
        Vector aggregateVector = new Vector(aggregate.logicalType(), 1);
        state.result().writeTo(aggregateVector, 0);
        Vector outputVector = expressionExecutor.execute(
                outputExpression,
                new DataChunk(List.of("#aggregate0"), List.of(aggregateVector))
        );
        return ScalarAggregateValue.fromVector(outputType, outputVector, 0);
    }

    private int bucket(long first, long second) {
        return mix64(first ^ Long.rotateLeft(second, 32)) & mask;
    }

    private static int mix64(long key) {
        long mixed = key + 0x9E3779B97F4A7C15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed = mixed ^ (mixed >>> 31);
        return (int) mixed;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        return result;
    }

    private static final class AggregateState {
        private final String name;
        private final LogicalType returnType;
        private long count;
        private long longSum;
        private double doubleSum;
        private ScalarAggregateValue value;

        private AggregateState(BoundAggregateExpression aggregate) {
            this.name = aggregate.name().toLowerCase(Locale.ROOT);
            this.returnType = aggregate.logicalType();
        }

        private void update(Vector inputVector, int rowIndex, boolean starArgument) {
            if (name.equals("count")) {
                if (starArgument || (inputVector != null && !inputVector.isNull(rowIndex))) {
                    count++;
                }
                return;
            }
            if (inputVector == null || inputVector.isNull(rowIndex)) {
                return;
            }
            switch (name) {
                case "sum" -> updateSum(inputVector, rowIndex);
                case "avg" -> updateAverage(inputVector, rowIndex);
                case "min" -> updateMin(ScalarAggregateValue.fromVector(inputVector.logicalType(), inputVector, rowIndex));
                case "max" -> updateMax(ScalarAggregateValue.fromVector(inputVector.logicalType(), inputVector, rowIndex));
                default -> throw new ExecutionException("Unsupported correlated scalar aggregate function: " + name);
            }
        }

        private ScalarAggregateValue result() {
            return switch (name) {
                case "count" -> ScalarAggregateValue.bigint(count);
                case "sum" -> sumResult();
                case "avg" -> count == 0 ? ScalarAggregateValue.nullValue(returnType) : ScalarAggregateValue.dbl(doubleSum / count);
                case "min", "max" -> value == null ? ScalarAggregateValue.nullValue(returnType) : value;
                default -> throw new ExecutionException("Unsupported correlated scalar aggregate function: " + name);
            };
        }

        private void updateSum(Vector vector, int rowIndex) {
            count++;
            if (returnType.equals(LogicalType.DOUBLE)) {
                doubleSum += numericAsDouble(vector, rowIndex);
            } else {
                longSum += numericAsLong(vector, rowIndex);
            }
        }

        private void updateAverage(Vector vector, int rowIndex) {
            count++;
            doubleSum += numericAsDouble(vector, rowIndex);
        }

        private void updateMin(ScalarAggregateValue input) {
            if (value == null || input.compareTo(value) < 0) {
                value = input;
            }
        }

        private void updateMax(ScalarAggregateValue input) {
            if (value == null || input.compareTo(value) > 0) {
                value = input;
            }
        }

        private ScalarAggregateValue sumResult() {
            if (count == 0) {
                return ScalarAggregateValue.nullValue(returnType);
            }
            if (returnType.equals(LogicalType.DOUBLE)) {
                return ScalarAggregateValue.dbl(doubleSum);
            }
            return ScalarAggregateValue.bigint(longSum);
        }

        private long numericAsLong(Vector vector, int rowIndex) {
            if (vector.logicalType().equals(LogicalType.INTEGER)) {
                return vector.getInteger(rowIndex);
            }
            if (vector.logicalType().equals(LogicalType.BIGINT)) {
                return vector.getBigint(rowIndex);
            }
            if (vector.logicalType().equals(LogicalType.DOUBLE)) {
                return (long) vector.getDouble(rowIndex);
            }
            throw new ExecutionException("Aggregate function expects numeric input");
        }

        private double numericAsDouble(Vector vector, int rowIndex) {
            if (vector.logicalType().equals(LogicalType.INTEGER)) {
                return vector.getInteger(rowIndex);
            }
            if (vector.logicalType().equals(LogicalType.BIGINT)) {
                return vector.getBigint(rowIndex);
            }
            if (vector.logicalType().equals(LogicalType.DOUBLE)) {
                return vector.getDouble(rowIndex);
            }
            throw new ExecutionException("Aggregate function expects numeric input");
        }
    }
}

record ScalarAggregateValue(
        LogicalType logicalType,
        boolean isNull,
        boolean booleanValue,
        int integerValue,
        long bigintValue,
        double doubleValue,
        String textValue,
        LocalDate dateValue
) implements Comparable<ScalarAggregateValue> {
    static ScalarAggregateValue nullValue(LogicalType logicalType) {
        return new ScalarAggregateValue(logicalType, true, false, 0, 0L, 0.0d, null, null);
    }

    static ScalarAggregateValue bigint(long value) {
        return new ScalarAggregateValue(LogicalType.BIGINT, false, false, 0, value, 0.0d, null, null);
    }

    static ScalarAggregateValue dbl(double value) {
        return new ScalarAggregateValue(LogicalType.DOUBLE, false, false, 0, 0L, value, null, null);
    }

    static ScalarAggregateValue fromVector(LogicalType logicalType, Vector vector, int rowIndex) {
        if (vector.isNull(rowIndex)) {
            return nullValue(logicalType);
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            return new ScalarAggregateValue(logicalType, false, vector.getBoolean(rowIndex), 0, 0L, 0.0d, null, null);
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            return new ScalarAggregateValue(logicalType, false, false, vector.getInteger(rowIndex), 0L, 0.0d, null, null);
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            return bigint(vector.getBigint(rowIndex));
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            return dbl(vector.getDouble(rowIndex));
        }
        if (logicalType.equals(LogicalType.TEXT)) {
            return new ScalarAggregateValue(logicalType, false, false, 0, 0L, 0.0d, vector.getText(rowIndex), null);
        }
        if (logicalType.equals(LogicalType.DATE)) {
            return new ScalarAggregateValue(logicalType, false, false, 0, 0L, 0.0d, null, vector.getDate(rowIndex));
        }
        throw new ExecutionException("Unsupported scalar aggregate value type: " + logicalType.id().name());
    }

    void writeTo(Vector vector, int rowIndex) {
        if (isNull) {
            vector.setNull(rowIndex);
            return;
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            vector.setBoolean(rowIndex, booleanValue);
        } else if (logicalType.equals(LogicalType.INTEGER)) {
            vector.setInteger(rowIndex, integerValue);
        } else if (logicalType.equals(LogicalType.BIGINT)) {
            vector.setBigint(rowIndex, bigintValue);
        } else if (logicalType.equals(LogicalType.DOUBLE)) {
            vector.setDouble(rowIndex, doubleValue);
        } else if (logicalType.equals(LogicalType.TEXT)) {
            vector.setText(rowIndex, textValue);
        } else if (logicalType.equals(LogicalType.DATE)) {
            vector.setDate(rowIndex, dateValue);
        } else {
            vector.setNull(rowIndex);
        }
    }

    @Override
    public int compareTo(ScalarAggregateValue other) {
        if (isNumeric() && other.isNumeric()) {
            return Double.compare(numericAsDouble(), other.numericAsDouble());
        }
        if (logicalType.equals(LogicalType.TEXT) && other.logicalType.equals(LogicalType.TEXT)) {
            return textValue.compareTo(other.textValue);
        }
        if (logicalType.equals(LogicalType.DATE) && other.logicalType.equals(LogicalType.DATE)) {
            return dateValue.compareTo(other.dateValue);
        }
        throw new ExecutionException("Cannot compare scalar aggregate values");
    }

    private boolean isNumeric() {
        return logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DOUBLE);
    }

    private double numericAsDouble() {
        if (logicalType.equals(LogicalType.INTEGER)) {
            return integerValue;
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            return bigintValue;
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            return doubleValue;
        }
        throw new ExecutionException("Scalar aggregate value is not numeric");
    }
}
