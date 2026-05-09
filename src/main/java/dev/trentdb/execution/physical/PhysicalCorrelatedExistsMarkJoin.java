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

public final class PhysicalCorrelatedExistsMarkJoin implements PhysicalOperator {
    private final StorageManager storageManager;
    private final List<String> outputNames;
    private final List<LogicalType> outputTypes;
    private final BoundExistsSubqueryExpression exists;
    private final BoundColumnRefExpression marker;
    private final ExpressionExecutor expressionExecutor;

    public PhysicalCorrelatedExistsMarkJoin(
            StorageManager storageManager,
            List<String> leftNames,
            List<LogicalType> leftTypes,
            BoundExistsSubqueryExpression exists,
            BoundColumnRefExpression marker,
            ExpressionExecutor expressionExecutor
    ) {
        this.storageManager = storageManager;
        this.outputNames = outputNames(leftNames, marker);
        this.outputTypes = outputTypes(leftTypes);
        this.exists = exists;
        this.marker = marker;
        this.expressionExecutor = expressionExecutor;
    }

    public BoundExistsSubqueryExpression exists() {
        return exists;
    }

    public BoundColumnRefExpression marker() {
        return marker;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.MARK_JOIN;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        long start = ExecutionProfiler.start();
        CorrelatedExistsLookup lookup = buildLookup();
        ExecutionProfiler.log(
                "PhysicalCorrelatedExistsMarkJoin",
                "build_mark_lookup",
                start,
                "keys=" + lookup.keyCount() + " keyType=" + lookup.keyType().id().name()
        );
        return new CorrelatedExistsLocalState(lookup);
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        CorrelatedExistsLocalState state = (CorrelatedExistsLocalState) operatorInput.localState();
        Vector markerVector = new Vector(LogicalType.BOOLEAN, input.cardinality());
        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            markerVector.setBoolean(rowIndex, state.lookup().matches(input, rowIndex));
        }
        ArrayList<Vector> vectors = new ArrayList<>(input.vectors());
        vectors.add(markerVector);
        downstream.accept(new DataChunk(outputNames, vectors));
    }

    private CorrelatedExistsLookup buildLookup() {
        BoundSelectStatement subquery = exists.subquery();
        if (!(subquery.from() instanceof BoundTableRef tableRef)) {
            throw new ExecutionException("Correlated EXISTS currently supports single-table scan subqueries only");
        }
        if (subquery.isAggregateQuery() || subquery.having() != null || !subquery.orderBy().isEmpty()
                || subquery.limit() != null) {
            throw new ExecutionException("Correlated EXISTS currently supports filtered scan subqueries only");
        }
        if (subquery.where() == null) {
            throw new ExecutionException("Correlated EXISTS currently requires a correlated equality predicate");
        }
        CorrelatedExistsPlan plan = correlatedExistsPlan(subquery.where());
        List<DataChunk> chunks = scanChunks(tableRef);
        LongHashSet keys = buildKeySet(chunks, plan);
        return new CorrelatedExistsLookup(plan.keyType(), plan.outerOrdinal(), keys);
    }

    private CorrelatedExistsPlan correlatedExistsPlan(BoundExpression predicate) {
        ArrayList<BoundExpression> conjuncts = new ArrayList<>();
        flattenConjuncts(predicate, conjuncts);
        CorrelatedEquality equality = null;
        ArrayList<BoundExpression> residuals = new ArrayList<>();
        for (BoundExpression conjunct : conjuncts) {
            CorrelatedEquality candidate = correlatedEquality(conjunct);
            if (candidate != null && equality == null) {
                equality = candidate;
                continue;
            }
            if (containsOuterReference(conjunct)) {
                throw new ExecutionException("Correlated EXISTS currently supports one correlated equality predicate");
            }
            residuals.add(conjunct);
        }
        if (equality == null) {
            throw new ExecutionException("Correlated EXISTS currently requires a correlated equality predicate");
        }
        return new CorrelatedExistsPlan(
                equality.innerOrdinal(),
                equality.outerOrdinal(),
                equality.keyType(),
                combineConjuncts(residuals)
        );
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
        if (leftInner != null) {
            return leftInner;
        }
        return correlatedEquality(right, left);
    }

    private CorrelatedEquality correlatedEquality(BoundColumnRefExpression inner, BoundColumnRefExpression outer) {
        if (inner.ordinal() >= exists.localColumnCount() || outer.ordinal() < exists.localColumnCount()) {
            return null;
        }
        LogicalType innerType = inner.logicalType();
        LogicalType outerType = outer.logicalType();
        if (!innerType.equals(outerType) || !supportsKeyType(innerType)) {
            throw new ExecutionException("Correlated EXISTS equality key type is not supported: "
                    + innerType.id().name() + " = " + outerType.id().name());
        }
        int correlatedIndex = outer.ordinal() - exists.localColumnCount();
        if (correlatedIndex < 0 || correlatedIndex >= exists.correlatedColumns().size()) {
            throw new ExecutionException("Correlated EXISTS outer reference is outside the bound correlation list");
        }
        int outerOrdinal = exists.correlatedColumns().get(correlatedIndex).outerOrdinal();
        return new CorrelatedEquality(inner.ordinal(), outerOrdinal, innerType);
    }

    private LongHashSet buildKeySet(List<DataChunk> chunks, CorrelatedExistsPlan plan) {
        LongHashSet keys = new LongHashSet(countRows(chunks));
        for (DataChunk chunk : chunks) {
            Vector residual = plan.residual() == null ? null : expressionExecutor.execute(plan.residual(), chunk);
            Vector keyVector = chunk.column(plan.innerOrdinal());
            for (int rowIndex = 0; rowIndex < chunk.cardinality(); rowIndex++) {
                if (keyVector.isNull(rowIndex) || !matchesResidual(residual, rowIndex)) {
                    continue;
                }
                keys.add(keyAsLong(keyVector, rowIndex, plan.keyType()));
            }
        }
        return keys;
    }

    private boolean matchesResidual(Vector residual, int rowIndex) {
        if (residual == null) {
            return true;
        }
        if (!residual.logicalType().equals(LogicalType.BOOLEAN)) {
            throw new ExecutionException("Correlated EXISTS residual predicate must evaluate to BOOLEAN");
        }
        return !residual.isNull(rowIndex) && residual.getBoolean(rowIndex);
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
            case BoundColumnRefExpression column -> column.ordinal() >= exists.localColumnCount();
            case BoundExistsSubqueryExpression ignored -> false;
            case BoundFunctionExpression function -> containsOuterReference(function.arguments());
            case BoundInExpression in -> containsOuterReference(in.input()) || containsOuterReference(in.candidates());
            case BoundInSubqueryExpression in -> containsOuterReference(in.input());
            case BoundIntervalExpression ignored -> false;
            case BoundLiteralExpression ignored -> false;
            case BoundOutputColumnExpression output -> output.ordinal() >= exists.localColumnCount();
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

    private void flattenConjuncts(BoundExpression expression, List<BoundExpression> output) {
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

    private long keyAsLong(Vector keyVector, int rowIndex, LogicalType keyType) {
        if (keyType.equals(LogicalType.BOOLEAN)) {
            return keyVector.getBoolean(rowIndex) ? 1L : 0L;
        }
        if (keyType.equals(LogicalType.INTEGER)) {
            return keyVector.getInteger(rowIndex);
        }
        if (keyType.equals(LogicalType.BIGINT)) {
            return keyVector.getBigint(rowIndex);
        }
        if (keyType.equals(LogicalType.DATE)) {
            return keyVector.getDate(rowIndex).toEpochDay();
        }
        throw new ExecutionException("Unsupported correlated EXISTS key type: " + keyType.id().name());
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

    private List<LogicalType> outputTypes(List<LogicalType> leftTypes) {
        ArrayList<LogicalType> types = new ArrayList<>(leftTypes.size() + 1);
        types.addAll(leftTypes);
        types.add(LogicalType.BOOLEAN);
        return List.copyOf(types);
    }

    private record CorrelatedEquality(int innerOrdinal, int outerOrdinal, LogicalType keyType) {
    }

    private record CorrelatedExistsPlan(
            int innerOrdinal,
            int outerOrdinal,
            LogicalType keyType,
            BoundExpression residual
    ) {
    }

    private record CorrelatedExistsLookup(LogicalType keyType, int outerOrdinal, LongHashSet keys) {
        boolean matches(DataChunk input, int rowIndex) {
            Vector keyVector = input.column(outerOrdinal);
            if (keyVector.isNull(rowIndex)) {
                return false;
            }
            return keys.contains(keyAsLong(keyVector, rowIndex, keyType));
        }

        int keyCount() {
            return keys.size();
        }

        private long keyAsLong(Vector keyVector, int rowIndex, LogicalType keyType) {
            if (keyType.equals(LogicalType.BOOLEAN)) {
                return keyVector.getBoolean(rowIndex) ? 1L : 0L;
            }
            if (keyType.equals(LogicalType.INTEGER)) {
                return keyVector.getInteger(rowIndex);
            }
            if (keyType.equals(LogicalType.BIGINT)) {
                return keyVector.getBigint(rowIndex);
            }
            if (keyType.equals(LogicalType.DATE)) {
                return keyVector.getDate(rowIndex).toEpochDay();
            }
            throw new ExecutionException("Unsupported correlated EXISTS key type: " + keyType.id().name());
        }
    }

    private static final class CorrelatedExistsLocalState extends LocalOperatorState {
        private final CorrelatedExistsLookup lookup;

        private CorrelatedExistsLocalState(CorrelatedExistsLookup lookup) {
            this.lookup = lookup;
        }

        private CorrelatedExistsLookup lookup() {
            return lookup;
        }
    }

    private static final class LongHashSet {
        private final long[] keys;
        private final boolean[] occupied;
        private final int mask;
        private int size;

        private LongHashSet(int rowCapacity) {
            int bucketCount = nextPowerOfTwo(Math.max(16, Math.max(1, rowCapacity) * 2));
            this.keys = new long[bucketCount];
            this.occupied = new boolean[bucketCount];
            this.mask = bucketCount - 1;
            this.size = 0;
        }

        private void add(long key) {
            int bucket = bucket(key);
            while (occupied[bucket]) {
                if (keys[bucket] == key) {
                    return;
                }
                bucket = (bucket + 1) & mask;
            }
            occupied[bucket] = true;
            keys[bucket] = key;
            size++;
        }

        private boolean contains(long key) {
            int bucket = bucket(key);
            while (occupied[bucket]) {
                if (keys[bucket] == key) {
                    return true;
                }
                bucket = (bucket + 1) & mask;
            }
            return false;
        }

        private int size() {
            return size;
        }

        private int bucket(long key) {
            return mix64(key) & mask;
        }

        private int mix64(long key) {
            long z = key + 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            return (int) z;
        }

        private static int nextPowerOfTwo(int value) {
            int result = 1;
            while (result < value) {
                result <<= 1;
            }
            return result;
        }
    }
}
