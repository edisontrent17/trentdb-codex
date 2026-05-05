package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.logical.LogicalPlanner;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.trentdb.planner.BoundExpressionTypes.logicalType;

final class SubqueryExpressionEvaluator {
    private static final byte SQL_UNKNOWN = -1;
    private static final byte SQL_FALSE = 0;
    private static final byte SQL_TRUE = 1;

    private final StorageManager storageManager;
    private final LogicalPlanner logicalPlanner = new LogicalPlanner();
    private final Map<BoundSelectStatement, QueryResult> subqueryCache = new HashMap<>();

    SubqueryExpressionEvaluator(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    Vector scalar(BoundSubqueryExpression subquery, DataChunk input) {
        QueryResult result = executeSubquery(subquery.subquery());
        if (result.rows().isEmpty()) {
            return constantValue(subquery.logicalType(), null, input.cardinality());
        }
        if (result.rows().size() > 1) {
            throw new ExecutionException("Scalar subquery returned more than one row");
        }
        Object value = result.rows().getFirst().getFirst();
        return constantValue(subquery.logicalType(), value, input.cardinality());
    }

    Vector in(BoundInSubqueryExpression in, DataChunk input, Vector inputValues) {
        QueryResult result = executeSubquery(in.subquery());
        LogicalType candidateType = logicalType(in.subquery().selectList().getFirst());
        Vector candidateVector = vectorFromRows(candidateType, result.rows());
        Vector output = new Vector(in.logicalType(), input.cardinality());

        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            byte value = evaluateIn(inputValues, rowIndex, candidateVector, result.rows().size());
            if (in.negated()) {
                value = negateSqlTruth(value);
            }
            writeSqlTruth(output, rowIndex, value);
        }
        return output;
    }

    private QueryResult executeSubquery(BoundSelectStatement subquery) {
        if (storageManager == null) {
            throw new ExecutionException("Subquery execution requires storage context");
        }
        QueryResult cached = subqueryCache.get(subquery);
        if (cached != null) {
            return cached;
        }
        QueryResult result = new QueryExecutor(storageManager).execute(logicalPlanner.plan(subquery));
        subqueryCache.put(subquery, result);
        return result;
    }

    private byte evaluateIn(Vector inputVector, int rowIndex, Vector candidateVector, int candidateCount) {
        if (inputVector.isNull(rowIndex)) {
            return SQL_UNKNOWN;
        }
        boolean hasNullCandidate = false;
        for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
            if (candidateVector.isNull(candidateIndex)) {
                hasNullCandidate = true;
                continue;
            }
            if (compareValues(inputVector, rowIndex, candidateVector, candidateIndex) == 0) {
                return SQL_TRUE;
            }
        }
        return hasNullCandidate ? SQL_UNKNOWN : SQL_FALSE;
    }

    private Vector vectorFromRows(LogicalType logicalType, List<List<Object>> rows) {
        Vector vector = new Vector(logicalType, rows.size());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            writeValue(vector, rowIndex, rows.get(rowIndex).getFirst(), logicalType);
        }
        return vector;
    }

    private Vector constantValue(LogicalType logicalType, Object value, int cardinality) {
        if (value == null || logicalType.equals(LogicalType.NULL)) {
            return Vector.constantNull(logicalType, cardinality);
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            return Vector.constantBoolean((Boolean) value, cardinality);
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            return Vector.constantInteger(((Number) value).intValue(), cardinality);
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            return Vector.constantBigint(((Number) value).longValue(), cardinality);
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            return Vector.constantDouble(((Number) value).doubleValue(), cardinality);
        }
        if (logicalType.equals(LogicalType.TEXT)) {
            return Vector.constantText((String) value, cardinality);
        }
        if (logicalType.equals(LogicalType.DATE)) {
            return Vector.constantDate((LocalDate) value, cardinality);
        }
        throw new ExecutionException("Unsupported subquery value type: " + logicalType.id().name());
    }

    private void writeValue(Vector vector, int rowIndex, Object value, LogicalType logicalType) {
        if (value == null) {
            vector.setNull(rowIndex);
            return;
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            vector.setBoolean(rowIndex, (Boolean) value);
            return;
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            vector.setInteger(rowIndex, ((Number) value).intValue());
            return;
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            vector.setBigint(rowIndex, ((Number) value).longValue());
            return;
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            vector.setDouble(rowIndex, ((Number) value).doubleValue());
            return;
        }
        if (logicalType.equals(LogicalType.TEXT)) {
            vector.setText(rowIndex, (String) value);
            return;
        }
        if (logicalType.equals(LogicalType.DATE)) {
            vector.setDate(rowIndex, (LocalDate) value);
            return;
        }
        throw new ExecutionException("Unsupported subquery value type: " + logicalType.id().name());
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
}
