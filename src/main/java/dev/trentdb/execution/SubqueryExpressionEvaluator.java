package dev.trentdb.execution;

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
import dev.trentdb.planner.BoundFrom;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundJoinRef;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundSubqueryRef;
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
        if (subquery.isCorrelated()) {
            return correlatedScalar(subquery, input);
        }
        QueryResult result = executeSubquery(subquery.subquery());
        Object value = scalarValue(result);
        return constantValue(subquery.logicalType(), value, input.cardinality());
    }

    private Vector correlatedScalar(BoundSubqueryExpression subquery, DataChunk input) {
        Vector output = new Vector(subquery.logicalType(), input.cardinality());
        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            QueryResult result = executeSubquery(rewriteSubquery(subquery, input, rowIndex));
            writeValue(output, rowIndex, scalarValue(result), subquery.logicalType());
        }
        return output;
    }

    private Object scalarValue(QueryResult result) {
        if (result.rows().isEmpty()) {
            return null;
        }
        if (result.rows().size() > 1) {
            throw new ExecutionException("Scalar subquery returned more than one row");
        }
        return result.rows().getFirst().getFirst();
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

    Vector exists(BoundExistsSubqueryExpression exists, DataChunk input) {
        if (exists.isCorrelated()) {
            throw new ExecutionException("Correlated EXISTS must be planned as a dependent MARK join");
        }
        boolean hasRows = !executeSubquery(exists.subquery()).rows().isEmpty();
        return Vector.constantBoolean(hasRows, input.cardinality());
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

    private BoundSelectStatement rewriteSubquery(BoundSubqueryExpression subquery, DataChunk input, int rowIndex) {
        return rewriteStatement(subquery.subquery(), subquery, input, rowIndex);
    }

    private BoundFrom rewriteFrom(BoundFrom from, BoundSubqueryExpression subquery, DataChunk input, int rowIndex) {
        if (from instanceof BoundJoinRef join) {
            return new BoundJoinRef(
                    rewriteFrom(join.left(), subquery, input, rowIndex),
                    join.right(),
                    rewriteExpression(join.condition(), subquery, input, rowIndex),
                    join.type()
            );
        }
        if (from instanceof BoundSubqueryRef subqueryRef) {
            BoundSelectStatement rewritten = rewriteStatement(subqueryRef.subquery(), subquery, input, rowIndex);
            return new BoundSubqueryRef(rewritten, subqueryRef.relationName(), subqueryRef.columns());
        }
        return from;
    }

    private BoundSelectStatement rewriteStatement(
            BoundSelectStatement statement,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        return new BoundSelectStatement(
                rewriteFrom(statement.from(), subquery, input, rowIndex),
                rewriteExpressions(statement.selectList(), subquery, input, rowIndex),
                statement.selectNames(),
                rewriteExpression(statement.where(), subquery, input, rowIndex),
                rewriteExpressions(statement.groupBy(), subquery, input, rowIndex),
                rewriteExpression(statement.having(), subquery, input, rowIndex),
                rewriteOrderBy(statement.orderBy(), subquery, input, rowIndex),
                statement.limit()
        );
    }

    private List<BoundExpression> rewriteExpressions(
            List<BoundExpression> expressions,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        return expressions.stream()
                .map(expression -> rewriteExpression(expression, subquery, input, rowIndex))
                .toList();
    }

    private List<BoundOrderByItem> rewriteOrderBy(
            List<BoundOrderByItem> orderBy,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        return orderBy.stream()
                .map(item -> new BoundOrderByItem(
                        rewriteExpression(item.expression(), subquery, input, rowIndex),
                        item.direction()
                ))
                .toList();
    }

    private BoundExpression rewriteExpression(
            BoundExpression expression,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        if (expression == null) {
            return null;
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> rewriteAggregate(aggregate, subquery, input, rowIndex);
            case BoundBetweenExpression between -> new BoundBetweenExpression(
                    rewriteExpression(between.input(), subquery, input, rowIndex),
                    rewriteExpression(between.lower(), subquery, input, rowIndex),
                    rewriteExpression(between.upper(), subquery, input, rowIndex)
            );
            case BoundBinaryExpression binary -> new BoundBinaryExpression(
                    rewriteExpression(binary.left(), subquery, input, rowIndex),
                    binary.operator(),
                    rewriteExpression(binary.right(), subquery, input, rowIndex),
                    binary.logicalType()
            );
            case BoundCaseExpression caseExpression -> rewriteCase(caseExpression, subquery, input, rowIndex);
            case BoundCastExpression cast -> new BoundCastExpression(
                    rewriteExpression(cast.child(), subquery, input, rowIndex),
                    cast.logicalType()
            );
            case BoundColumnRefExpression column -> rewriteColumn(column, subquery, input, rowIndex);
            case BoundFunctionExpression function -> new BoundFunctionExpression(
                    function.function(),
                    rewriteExpressions(function.arguments(), subquery, input, rowIndex)
            );
            case BoundInExpression in -> new BoundInExpression(
                    rewriteExpression(in.input(), subquery, input, rowIndex),
                    rewriteExpressions(in.candidates(), subquery, input, rowIndex),
                    in.negated()
            );
            case BoundInSubqueryExpression in -> new BoundInSubqueryExpression(
                    rewriteExpression(in.input(), subquery, input, rowIndex),
                    in.subquery(),
                    in.negated()
            );
            case BoundExistsSubqueryExpression exists -> exists;
            case BoundSubqueryExpression nested -> nested;
            case BoundOutputColumnExpression output -> output;
            case BoundLiteralExpression literal -> literal;
            case BoundIntervalExpression interval -> interval;
        };
    }

    private BoundAggregateExpression rewriteAggregate(
            BoundAggregateExpression aggregate,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        return new BoundAggregateExpression(
                aggregate.function(),
                rewriteExpressions(aggregate.arguments(), subquery, input, rowIndex),
                aggregate.starArgument(),
                aggregate.distinct()
        );
    }

    private BoundCaseExpression rewriteCase(
            BoundCaseExpression caseExpression,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        List<BoundCaseExpression.WhenClause> branches = caseExpression.branches().stream()
                .map(branch -> new BoundCaseExpression.WhenClause(
                        rewriteExpression(branch.condition(), subquery, input, rowIndex),
                        rewriteExpression(branch.result(), subquery, input, rowIndex)
                ))
                .toList();
        return new BoundCaseExpression(
                branches,
                rewriteExpression(caseExpression.elseExpression(), subquery, input, rowIndex),
                caseExpression.logicalType()
        );
    }

    private BoundExpression rewriteColumn(
            BoundColumnRefExpression column,
            BoundSubqueryExpression subquery,
            DataChunk input,
            int rowIndex
    ) {
        if (column.ordinal() < subquery.localColumnCount()) {
            return column;
        }
        int correlatedIndex = column.ordinal() - subquery.localColumnCount();
        if (correlatedIndex < 0 || correlatedIndex >= subquery.correlatedColumns().size()) {
            throw new ExecutionException("Correlated scalar subquery outer reference is outside the bound correlation list");
        }
        BoundExistsSubqueryExpression.CorrelatedColumn correlated = subquery.correlatedColumns().get(correlatedIndex);
        Vector outerVector = input.column(correlated.outerOrdinal());
        Object value = outerVector.isNull(rowIndex) ? null : readValue(outerVector, rowIndex, correlated.logicalType());
        return new BoundLiteralExpression(correlated.logicalType(), value);
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

    private Object readValue(Vector vector, int rowIndex, LogicalType logicalType) {
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            return vector.getBoolean(rowIndex);
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            return vector.getInteger(rowIndex);
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            return vector.getBigint(rowIndex);
        }
        if (logicalType.equals(LogicalType.DOUBLE)) {
            return vector.getDouble(rowIndex);
        }
        if (logicalType.equals(LogicalType.TEXT)) {
            return vector.getText(rowIndex);
        }
        if (logicalType.equals(LogicalType.DATE)) {
            return vector.getDate(rowIndex);
        }
        throw new ExecutionException("Unsupported correlated subquery value type: " + logicalType.id().name());
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
