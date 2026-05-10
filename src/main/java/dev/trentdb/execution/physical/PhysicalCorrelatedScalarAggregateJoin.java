package dev.trentdb.execution.physical;

import dev.trentdb.ast.BinaryOperator;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExecutionProfiler;
import dev.trentdb.execution.ExpressionExecutor;
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
import dev.trentdb.planner.BoundSelectStatement;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.planner.BoundTableRef;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalCorrelatedScalarAggregateJoin implements PhysicalOperator {
    private final StorageManager storageManager;
    private final List<String> outputNames;
    private final List<LogicalType> outputTypes;
    private final BoundSubqueryExpression scalarSubquery;
    private final BoundColumnRefExpression marker;
    private final ExpressionExecutor expressionExecutor;

    public PhysicalCorrelatedScalarAggregateJoin(
            StorageManager storageManager,
            List<String> leftNames,
            List<LogicalType> leftTypes,
            BoundSubqueryExpression scalarSubquery,
            BoundColumnRefExpression marker,
            ExpressionExecutor expressionExecutor
    ) {
        this.storageManager = storageManager;
        this.outputNames = outputNames(leftNames, marker);
        this.outputTypes = outputTypes(leftTypes, marker);
        this.scalarSubquery = scalarSubquery;
        this.marker = marker;
        this.expressionExecutor = expressionExecutor;
    }

    public BoundSubqueryExpression scalarSubquery() {
        return scalarSubquery;
    }

    public BoundColumnRefExpression marker() {
        return marker;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.SINGLE_JOIN;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        long start = ExecutionProfiler.start();
        ScalarAggregatePlan plan = scalarAggregatePlan();
        ScalarAggregateLookup lookup = buildLookup(plan);
        ExecutionProfiler.log(
                "PhysicalCorrelatedScalarAggregateJoin",
                "build_single_lookup",
                start,
                "keys=" + lookup.keyCount() + " keyColumns=" + plan.equalities().size()
        );
        return new ScalarAggregateLocalState(lookup);
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        ScalarAggregateLocalState state = (ScalarAggregateLocalState) operatorInput.localState();
        Vector markerVector = new Vector(marker.logicalType(), input.cardinality());
        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            state.lookup().writeValue(input, rowIndex, markerVector);
        }
        ArrayList<Vector> vectors = new ArrayList<>(input.vectors());
        vectors.add(markerVector);
        downstream.accept(new DataChunk(outputNames, vectors));
    }

    private ScalarAggregateLookup buildLookup(ScalarAggregatePlan plan) {
        List<DataChunk> chunks = scanChunks(plan.table());
        ScalarAggregateHashTable aggregates = new ScalarAggregateHashTable(plan.equalities().size(), countRows(chunks));
        for (DataChunk chunk : chunks) {
            Vector residual = plan.residual() == null ? null : expressionExecutor.execute(plan.residual(), chunk);
            Vector argument = plan.aggregate().starArgument()
                    ? null
                    : expressionExecutor.execute(plan.aggregate().arguments().getFirst(), chunk);
            for (int rowIndex = 0; rowIndex < chunk.cardinality(); rowIndex++) {
                if (!matchesResidual(residual, rowIndex)) {
                    continue;
                }
                RowKey key = innerKey(plan.equalities(), chunk, rowIndex);
                if (key.hasNull()) {
                    continue;
                }
                aggregates.update(
                        key.first(),
                        key.second(),
                        plan.aggregate(),
                        argument,
                        rowIndex,
                        plan.aggregate().starArgument()
                );
            }
        }
        aggregates.finish(plan.aggregate(), plan.outputExpression(), marker.logicalType(), expressionExecutor);
        ScalarAggregateValue emptyResult = ScalarAggregateHashTable.emptyResult(
                plan.aggregate(),
                plan.outputExpression(),
                marker.logicalType(),
                expressionExecutor
        );
        return new ScalarAggregateLookup(plan.equalities(), aggregates, emptyResult);
    }

    private ScalarAggregatePlan scalarAggregatePlan() {
        BoundSelectStatement statement = scalarSubquery.subquery();
        if (!(statement.from() instanceof BoundTableRef table)) {
            throw new ExecutionException("Correlated scalar aggregate currently supports single-table subqueries only");
        }
        if (!statement.isAggregateQuery() || !statement.groupBy().isEmpty() || statement.having() != null
                || !statement.orderBy().isEmpty() || statement.limit() != null || statement.selectList().size() != 1) {
            throw new ExecutionException("Correlated scalar aggregate currently supports one ungrouped aggregate output");
        }
        AggregateRewrite output = rewriteAggregateOutput(statement.selectList().getFirst());
        if (output.aggregate() == null) {
            throw new ExecutionException("Correlated scalar aggregate requires an aggregate expression");
        }

        ArrayList<BoundExpression> residuals = new ArrayList<>();
        ArrayList<CorrelatedEquality> equalities = new ArrayList<>();
        ArrayList<BoundExpression> conjuncts = new ArrayList<>();
        flattenConjuncts(statement.where(), conjuncts);
        for (BoundExpression conjunct : conjuncts) {
            CorrelatedEquality equality = correlatedEquality(conjunct);
            if (equality != null) {
                equalities.add(equality);
            } else if (containsOuterReference(conjunct)) {
                throw new ExecutionException("Correlated scalar aggregate currently supports equality predicates only");
            } else {
                residuals.add(conjunct);
            }
        }
        if (equalities.isEmpty() || equalities.size() > 2) {
            throw new ExecutionException("Correlated scalar aggregate currently supports one or two equality keys");
        }
        return new ScalarAggregatePlan(table, equalities, combineConjuncts(residuals), output.aggregate(), output.expression());
    }

    private AggregateRewrite rewriteAggregateOutput(BoundExpression expression) {
        if (expression instanceof BoundAggregateExpression aggregate) {
            if (aggregate.distinct()) {
                throw new ExecutionException("Correlated scalar aggregate does not support DISTINCT aggregates yet");
            }
            return new AggregateRewrite(
                    aggregate,
                    new BoundOutputColumnExpression("#aggregate0", 0, aggregate.logicalType())
            );
        }
        if (expression instanceof BoundBinaryExpression binary) {
            AggregateRewrite left = rewriteAggregateOutput(binary.left());
            AggregateRewrite right = rewriteAggregateOutput(binary.right());
            return new AggregateRewrite(
                    combineAggregate(left.aggregate(), right.aggregate()),
                    new BoundBinaryExpression(left.expression(), binary.operator(), right.expression(), binary.logicalType())
            );
        }
        if (expression instanceof BoundCastExpression cast) {
            AggregateRewrite child = rewriteAggregateOutput(cast.child());
            return new AggregateRewrite(child.aggregate(), new BoundCastExpression(child.expression(), cast.logicalType()));
        }
        if (expression instanceof BoundLiteralExpression literal) {
            return new AggregateRewrite(null, literal);
        }
        throw new ExecutionException("Unsupported correlated scalar aggregate output expression");
    }

    private BoundAggregateExpression combineAggregate(BoundAggregateExpression left, BoundAggregateExpression right) {
        if (left == null) {
            return right;
        }
        if (right == null || left.equals(right)) {
            return left;
        }
        throw new ExecutionException("Correlated scalar aggregate currently supports one aggregate expression");
    }

    private CorrelatedEquality correlatedEquality(BoundExpression expression) {
        if (!(expression instanceof BoundBinaryExpression binary) || binary.operator() != BinaryOperator.EQUAL) {
            return null;
        }
        if (!(binary.left() instanceof BoundColumnRefExpression left)
                || !(binary.right() instanceof BoundColumnRefExpression right)) {
            return null;
        }
        CorrelatedEquality leftInner = correlatedEquality(left, right);
        return leftInner == null ? correlatedEquality(right, left) : leftInner;
    }

    private CorrelatedEquality correlatedEquality(BoundColumnRefExpression inner, BoundColumnRefExpression outer) {
        if (inner.ordinal() >= scalarSubquery.localColumnCount() || outer.ordinal() < scalarSubquery.localColumnCount()) {
            return null;
        }
        if (!inner.logicalType().equals(outer.logicalType()) || !supportsKeyType(inner.logicalType())) {
            throw new ExecutionException("Correlated scalar aggregate equality key type is not supported");
        }
        int correlatedIndex = outer.ordinal() - scalarSubquery.localColumnCount();
        if (correlatedIndex < 0 || correlatedIndex >= scalarSubquery.correlatedColumns().size()) {
            throw new ExecutionException("Correlated scalar aggregate outer reference is outside the correlation list");
        }
        int outerOrdinal = scalarSubquery.correlatedColumns().get(correlatedIndex).outerOrdinal();
        return new CorrelatedEquality(inner.ordinal(), outerOrdinal, inner.logicalType());
    }

    private RowKey innerKey(List<CorrelatedEquality> equalities, DataChunk chunk, int rowIndex) {
        KeyValue first = keyValue(chunk.column(equalities.get(0).innerOrdinal()), rowIndex, equalities.get(0).keyType());
        if (first.isNull()) {
            return RowKey.nullKey();
        }
        KeyValue second = KeyValue.zero();
        if (equalities.size() == 2) {
            second = keyValue(chunk.column(equalities.get(1).innerOrdinal()), rowIndex, equalities.get(1).keyType());
            if (second.isNull()) {
                return RowKey.nullKey();
            }
        }
        return new RowKey(false, first.value(), second.value());
    }

    private RowKey outerKey(List<CorrelatedEquality> equalities, DataChunk chunk, int rowIndex) {
        KeyValue first = keyValue(chunk.column(equalities.get(0).outerOrdinal()), rowIndex, equalities.get(0).keyType());
        if (first.isNull()) {
            return RowKey.nullKey();
        }
        KeyValue second = KeyValue.zero();
        if (equalities.size() == 2) {
            second = keyValue(chunk.column(equalities.get(1).outerOrdinal()), rowIndex, equalities.get(1).keyType());
            if (second.isNull()) {
                return RowKey.nullKey();
            }
        }
        return new RowKey(false, first.value(), second.value());
    }

    private KeyValue keyValue(Vector vector, int rowIndex, LogicalType logicalType) {
        if (vector.isNull(rowIndex)) {
            return KeyValue.nullValue();
        }
        if (logicalType.equals(LogicalType.BOOLEAN)) {
            return new KeyValue(false, vector.getBoolean(rowIndex) ? 1L : 0L);
        }
        if (logicalType.equals(LogicalType.INTEGER)) {
            return new KeyValue(false, vector.getInteger(rowIndex));
        }
        if (logicalType.equals(LogicalType.BIGINT)) {
            return new KeyValue(false, vector.getBigint(rowIndex));
        }
        if (logicalType.equals(LogicalType.DATE)) {
            return new KeyValue(false, vector.getDate(rowIndex).toEpochDay());
        }
        throw new ExecutionException("Unsupported correlated scalar aggregate key type: " + logicalType.id().name());
    }

    private boolean containsOuterReference(BoundExpression expression) {
        if (expression == null) {
            return false;
        }
        return switch (expression) {
            case BoundAggregateExpression aggregate -> containsOuterReference(aggregate.arguments());
            case BoundBetweenExpression between -> containsOuterReference(between.input())
                    || containsOuterReference(between.lower())
                    || containsOuterReference(between.upper());
            case BoundBinaryExpression binary -> containsOuterReference(binary.left()) || containsOuterReference(binary.right());
            case BoundCaseExpression caseExpression -> containsOuterReference(caseExpression);
            case BoundCastExpression cast -> containsOuterReference(cast.child());
            case BoundColumnRefExpression column -> column.ordinal() >= scalarSubquery.localColumnCount();
            case BoundFunctionExpression function -> containsOuterReference(function.arguments());
            case BoundInExpression in -> containsOuterReference(in.input()) || containsOuterReference(in.candidates());
            case BoundOutputColumnExpression output -> output.ordinal() >= scalarSubquery.localColumnCount();
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundInSubqueryExpression in -> containsOuterReference(in.input());
            case BoundIntervalExpression ignored -> false;
            case BoundLiteralExpression ignored -> false;
            case BoundSubqueryExpression ignored -> false;
        };
    }

    private boolean containsOuterReference(List<BoundExpression> expressions) {
        for (BoundExpression expression : expressions) {
            if (containsOuterReference(expression)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOuterReference(BoundCaseExpression caseExpression) {
        if (containsOuterReference(caseExpression.elseExpression())) {
            return true;
        }
        for (BoundCaseExpression.WhenClause branch : caseExpression.branches()) {
            if (containsOuterReference(branch.condition()) || containsOuterReference(branch.result())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesResidual(Vector residual, int rowIndex) {
        if (residual == null) {
            return true;
        }
        if (!residual.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Correlated scalar aggregate residual predicate must evaluate to BOOLEAN");
        }
        return !residual.isNull(rowIndex) && residual.getBoolean(rowIndex);
    }

    private void flattenConjuncts(BoundExpression expression, List<BoundExpression> output) {
        if (expression == null) {
            return;
        }
        if (expression instanceof BoundBinaryExpression binary && binary.operator() == BinaryOperator.AND) {
            flattenConjuncts(binary.left(), output);
            flattenConjuncts(binary.right(), output);
            return;
        }
        output.add(expression);
    }

    private BoundExpression combineConjuncts(List<BoundExpression> conjuncts) {
        if (conjuncts.isEmpty()) {
            return null;
        }
        BoundExpression result = conjuncts.getFirst();
        for (int index = 1; index < conjuncts.size(); index++) {
            result = new BoundBinaryExpression(result, BinaryOperator.AND, conjuncts.get(index), LogicalType.BOOLEAN);
        }
        return result;
    }

    private List<DataChunk> scanChunks(BoundTableRef tableRef) {
        if (tableRef.isReplacementScan()) {
            return tableRef.replacementScan().scanFunction().scan();
        }
        return storageManager.getTable(tableRef.table()).scanChunks();
    }

    private int countRows(List<DataChunk> chunks) {
        int rowCount = 0;
        for (DataChunk chunk : chunks) {
            rowCount += chunk.cardinality();
        }
        return rowCount;
    }

    private boolean supportsKeyType(LogicalType logicalType) {
        return logicalType.equals(LogicalType.BOOLEAN)
                || logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DATE);
    }

    private List<String> outputNames(List<String> leftNames, BoundColumnRefExpression marker) {
        ArrayList<String> names = new ArrayList<>(leftNames.size() + 1);
        names.addAll(leftNames);
        names.add(marker.name());
        return List.copyOf(names);
    }

    private List<LogicalType> outputTypes(List<LogicalType> leftTypes, BoundColumnRefExpression marker) {
        ArrayList<LogicalType> types = new ArrayList<>(leftTypes.size() + 1);
        types.addAll(leftTypes);
        types.add(marker.logicalType());
        return List.copyOf(types);
    }

    private record ScalarAggregatePlan(
            BoundTableRef table,
            List<CorrelatedEquality> equalities,
            BoundExpression residual,
            BoundAggregateExpression aggregate,
            BoundExpression outputExpression
    ) {
        private ScalarAggregatePlan {
            equalities = List.copyOf(equalities);
        }
    }

    private record AggregateRewrite(BoundAggregateExpression aggregate, BoundExpression expression) {
    }

    private record CorrelatedEquality(int innerOrdinal, int outerOrdinal, LogicalType keyType) {
    }

    private record RowKey(boolean hasNull, long first, long second) {
        private static RowKey nullKey() {
            return new RowKey(true, 0L, 0L);
        }
    }

    private record KeyValue(boolean isNull, long value) {
        private static KeyValue nullValue() {
            return new KeyValue(true, 0L);
        }

        private static KeyValue zero() {
            return new KeyValue(false, 0L);
        }
    }

    private final class ScalarAggregateLookup {
        private final List<CorrelatedEquality> equalities;
        private final ScalarAggregateHashTable results;
        private final ScalarAggregateValue emptyResult;

        private ScalarAggregateLookup(
                List<CorrelatedEquality> equalities,
                ScalarAggregateHashTable results,
                ScalarAggregateValue emptyResult
        ) {
            this.equalities = List.copyOf(equalities);
            this.results = results;
            this.emptyResult = emptyResult;
        }

        private void writeValue(DataChunk input, int rowIndex, Vector output) {
            RowKey key = outerKey(equalities, input, rowIndex);
            if (key.hasNull()) {
                emptyResult.writeTo(output, rowIndex);
                return;
            }
            ScalarAggregateValue value = results.result(key.first(), key.second());
            if (value == null) {
                emptyResult.writeTo(output, rowIndex);
                return;
            }
            value.writeTo(output, rowIndex);
        }

        private int keyCount() {
            return results.size();
        }
    }

    private static final class ScalarAggregateLocalState extends LocalOperatorState {
        private final ScalarAggregateLookup lookup;

        private ScalarAggregateLocalState(ScalarAggregateLookup lookup) {
            this.lookup = lookup;
        }

        private ScalarAggregateLookup lookup() {
            return lookup;
        }
    }
}
