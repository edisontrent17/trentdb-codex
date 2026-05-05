package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundCaseExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundInExpression;
import dev.trentdb.planner.BoundInSubqueryExpression;
import dev.trentdb.planner.BoundIntervalExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.planner.BoundOutputColumnExpression;
import dev.trentdb.planner.BoundSubqueryExpression;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class PhysicalHashAggregate implements PhysicalOperator {
    private final List<BoundExpression> groups;
    private final List<BoundExpression> selectList;
    private final List<String> selectNames;
    private final ExpressionExecutor expressionExecutor;

    public PhysicalHashAggregate(List<BoundExpression> groups, List<BoundExpression> selectList, List<String> selectNames) {
        this(groups, selectList, selectNames, null);
    }

    public PhysicalHashAggregate(
            List<BoundExpression> groups,
            List<BoundExpression> selectList,
            List<String> selectNames,
            StorageManager storageManager
    ) {
        this.groups = List.copyOf(groups);
        this.selectList = List.copyOf(selectList);
        this.selectNames = List.copyOf(selectNames);
        this.expressionExecutor = new ExpressionExecutor(storageManager);
    }

    public List<BoundExpression> groups() {
        return groups;
    }

    public List<BoundExpression> selectList() {
        return selectList;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.HASH_GROUP_BY;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new AggregateLocalState(groups.isEmpty(), selectList);
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        AggregateLocalState state = (AggregateLocalState) operatorInput.localState();
        List<Vector> aggregateVectors = aggregateArgumentVectors(input);
        if (groups.isEmpty()) {
            AggregateRow row = state.ungroupedRow(selectList);
            updateRows(row, aggregateVectors, input.cardinality());
            return;
        }

        List<Vector> groupVectors = groups.stream()
                .map(group -> expressionExecutor.execute(group, input))
                .toList();
        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            GroupKey key = groupKey(groupVectors, rowIndex);
            AggregateRow row = state.groups.computeIfAbsent(key, ignored -> new AggregateRow(key.values(), selectList));
            updateRow(row, aggregateVectors, rowIndex);
        }
    }

    @Override
    public void finish(OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        AggregateLocalState state = (AggregateLocalState) operatorInput.localState();
        if (groups.isEmpty()) {
            downstream.accept(chunk(List.of(state.ungroupedRow(selectList)), 0, 1));
            return;
        }
        ArrayList<AggregateRow> rows = new ArrayList<>(state.groups.values());
        for (int offset = 0; offset < rows.size(); offset += InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
            int size = Math.min(InMemoryTableStorage.STANDARD_VECTOR_SIZE, rows.size() - offset);
            downstream.accept(chunk(rows, offset, size));
        }
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        throw new UnsupportedOperationException("PhysicalHashAggregate requires operator state");
    }

    private void updateRows(AggregateRow row, List<Vector> aggregateVectors, int cardinality) {
        for (int rowIndex = 0; rowIndex < cardinality; rowIndex++) {
            updateRow(row, aggregateVectors, rowIndex);
        }
    }

    private void updateRow(AggregateRow row, List<Vector> aggregateVectors, int rowIndex) {
        for (int selectIndex = 0; selectIndex < selectList.size(); selectIndex++) {
            if (selectList.get(selectIndex) instanceof BoundAggregateExpression aggregate) {
                Vector argumentVector = aggregateVectors.get(selectIndex);
                row.states[selectIndex].update(argumentVector, rowIndex, aggregate.starArgument());
            }
        }
    }

    private List<Vector> aggregateArgumentVectors(DataChunk input) {
        ArrayList<Vector> vectors = new ArrayList<>(selectList.size());
        for (BoundExpression expression : selectList) {
            if (expression instanceof BoundAggregateExpression aggregate && !aggregate.starArgument()) {
                vectors.add(expressionExecutor.execute(aggregate.arguments().getFirst(), input));
            } else {
                vectors.add(null);
            }
        }
        return vectors;
    }

    private GroupKey groupKey(List<Vector> groupVectors, int rowIndex) {
        ArrayList<Cell> values = new ArrayList<>(groupVectors.size());
        for (Vector vector : groupVectors) {
            values.add(Cell.fromVector(vector, rowIndex));
        }
        return new GroupKey(values);
    }

    private DataChunk chunk(List<AggregateRow> rows, int offset, int size) {
        ArrayList<Vector> vectors = new ArrayList<>(selectList.size());
        for (int selectIndex = 0; selectIndex < selectList.size(); selectIndex++) {
            BoundExpression expression = selectList.get(selectIndex);
            Vector vector = new Vector(logicalType(expression), size);
            for (int rowIndex = 0; rowIndex < size; rowIndex++) {
                Cell value = value(rows.get(offset + rowIndex), expression, selectIndex);
                value.writeTo(vector, rowIndex);
            }
            vectors.add(vector);
        }
        return new DataChunk(selectNames, vectors);
    }

    private Cell value(AggregateRow row, BoundExpression expression, int selectIndex) {
        if (expression instanceof BoundAggregateExpression) {
            return row.states[selectIndex].resultCell();
        }
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            if (groups.get(groupIndex).equals(expression)) {
                return row.groupValues.get(groupIndex);
            }
        }
        throw new ExecutionException("Aggregate output expression is not a group or aggregate");
    }

    private LogicalType logicalType(BoundExpression expression) {
        return switch (expression) {
            case BoundAggregateExpression aggregate -> aggregate.logicalType();
            case BoundBetweenExpression between -> between.logicalType();
            case BoundBinaryExpression binary -> binary.logicalType();
            case BoundCaseExpression caseExpression -> caseExpression.logicalType();
            case BoundCastExpression cast -> cast.logicalType();
            case BoundColumnRefExpression column -> column.logicalType();
            case BoundFunctionExpression function -> function.logicalType();
            case BoundInExpression in -> in.logicalType();
            case BoundInSubqueryExpression in -> in.logicalType();
            case BoundIntervalExpression ignored -> LogicalType.INTERVAL;
            case BoundLiteralExpression literal -> literal.logicalType();
            case BoundOutputColumnExpression output -> output.logicalType();
            case BoundSubqueryExpression subquery -> subquery.logicalType();
        };
    }

    private record GroupKey(List<Cell> values) {
        private GroupKey {
            values = List.copyOf(values);
        }
    }

    private static final class AggregateLocalState extends LocalOperatorState {
        private final LinkedHashMap<GroupKey, AggregateRow> groups = new LinkedHashMap<>();
        private AggregateRow ungroupedRow;

        private AggregateLocalState(boolean ungrouped, List<BoundExpression> selectList) {
            if (selectList.isEmpty()) {
                throw new IllegalArgumentException("Aggregate select list must not be empty");
            }
            if (ungrouped) {
                ungroupedRow = new AggregateRow(List.of(), selectList);
            }
        }

        private AggregateRow ungroupedRow(List<BoundExpression> selectList) {
            if (ungroupedRow == null) {
                ungroupedRow = new AggregateRow(List.of(), selectList);
            }
            return ungroupedRow;
        }
    }

    private static final class AggregateRow {
        private final List<Cell> groupValues;
        private final AggregateState[] states;

        private AggregateRow(List<Cell> groupValues, List<BoundExpression> selectList) {
            this.groupValues = List.copyOf(groupValues);
            this.states = new AggregateState[selectList.size()];
            for (int index = 0; index < selectList.size(); index++) {
                if (selectList.get(index) instanceof BoundAggregateExpression aggregate) {
                    states[index] = new AggregateState(aggregate);
                }
            }
        }
    }

    private static final class AggregateState {
        private final String name;
        private final LogicalType returnType;
        private long count;
        private double doubleSum;
        private long longSum;
        private Cell value;

        private AggregateState(BoundAggregateExpression aggregate) {
            this.name = aggregate.name().toLowerCase(java.util.Locale.ROOT);
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
                case "min" -> updateMin(Cell.fromVector(inputVector, rowIndex));
                case "max" -> updateMax(Cell.fromVector(inputVector, rowIndex));
                default -> throw new ExecutionException("Unsupported aggregate function: " + name);
            }
        }

        private Cell resultCell() {
            return switch (name) {
                case "count" -> Cell.bigint(count);
                case "sum" -> sumResult();
                case "avg" -> count == 0 ? Cell.nullCell(returnType) : Cell.dbl(doubleSum / count);
                case "min", "max" -> value == null ? Cell.nullCell(returnType) : value;
                default -> throw new ExecutionException("Unsupported aggregate function: " + name);
            };
        }

        private void updateSum(Vector inputVector, int rowIndex) {
            count++;
            if (returnType.equals(LogicalType.DOUBLE)) {
                doubleSum += numericAsDouble(inputVector, rowIndex);
            } else {
                longSum += numericAsLong(inputVector, rowIndex);
            }
        }

        private void updateAverage(Vector inputVector, int rowIndex) {
            count++;
            doubleSum += numericAsDouble(inputVector, rowIndex);
        }

        private Cell sumResult() {
            if (count == 0) {
                return Cell.nullCell(returnType);
            }
            if (returnType.equals(LogicalType.DOUBLE)) {
                return Cell.dbl(doubleSum);
            }
            return Cell.bigint(longSum);
        }

        private void updateMin(Cell input) {
            if (value == null || input.compareTo(value) < 0) {
                value = input;
            }
        }

        private void updateMax(Cell input) {
            if (value == null || input.compareTo(value) > 0) {
                value = input;
            }
        }

        private double numericAsDouble(Vector vector, int rowIndex) {
            LogicalType type = vector.logicalType();
            if (type.equals(LogicalType.INTEGER)) {
                return vector.getInteger(rowIndex);
            }
            if (type.equals(LogicalType.BIGINT)) {
                return vector.getBigint(rowIndex);
            }
            if (type.equals(LogicalType.DOUBLE)) {
                return vector.getDouble(rowIndex);
            }
            throw new ExecutionException("Aggregate function expects numeric input");
        }

        private long numericAsLong(Vector vector, int rowIndex) {
            LogicalType type = vector.logicalType();
            if (type.equals(LogicalType.INTEGER)) {
                return vector.getInteger(rowIndex);
            }
            if (type.equals(LogicalType.BIGINT)) {
                return vector.getBigint(rowIndex);
            }
            if (type.equals(LogicalType.DOUBLE)) {
                return (long) vector.getDouble(rowIndex);
            }
            throw new ExecutionException("Aggregate function expects numeric input");
        }
    }

    private record Cell(
            LogicalType logicalType,
            boolean isNull,
            boolean booleanValue,
            int integerValue,
            long bigintValue,
            double doubleValue,
            String textValue,
            LocalDate dateValue
    ) {
        private static Cell nullCell(LogicalType logicalType) {
            return new Cell(logicalType, true, false, 0, 0L, 0.0d, null, null);
        }

        private static Cell bool(boolean value) {
            return new Cell(LogicalType.BOOLEAN, false, value, 0, 0L, 0.0d, null, null);
        }

        private static Cell integer(int value) {
            return new Cell(LogicalType.INTEGER, false, false, value, 0L, 0.0d, null, null);
        }

        private static Cell bigint(long value) {
            return new Cell(LogicalType.BIGINT, false, false, 0, value, 0.0d, null, null);
        }

        private static Cell dbl(double value) {
            return new Cell(LogicalType.DOUBLE, false, false, 0, 0L, value, null, null);
        }

        private static Cell text(String value) {
            return value == null ? nullCell(LogicalType.TEXT)
                    : new Cell(LogicalType.TEXT, false, false, 0, 0L, 0.0d, value, null);
        }

        private static Cell date(LocalDate value) {
            return value == null ? nullCell(LogicalType.DATE)
                    : new Cell(LogicalType.DATE, false, false, 0, 0L, 0.0d, null, value);
        }

        private static Cell fromVector(Vector vector, int rowIndex) {
            if (vector.isNull(rowIndex)) {
                return nullCell(vector.logicalType());
            }
            LogicalType type = vector.logicalType();
            if (type.equals(LogicalType.BOOLEAN)) {
                return bool(vector.getBoolean(rowIndex));
            }
            if (type.equals(LogicalType.INTEGER)) {
                return integer(vector.getInteger(rowIndex));
            }
            if (type.equals(LogicalType.BIGINT)) {
                return bigint(vector.getBigint(rowIndex));
            }
            if (type.equals(LogicalType.DOUBLE)) {
                return dbl(vector.getDouble(rowIndex));
            }
            if (type.equals(LogicalType.TEXT)) {
                return text(vector.getText(rowIndex));
            }
            if (type.equals(LogicalType.DATE)) {
                return date(vector.getDate(rowIndex));
            }
            return nullCell(type);
        }

        private void writeTo(Vector vector, int rowIndex) {
            if (isNull) {
                vector.setNull(rowIndex);
                return;
            }
            if (logicalType.equals(LogicalType.BOOLEAN)) {
                vector.setBoolean(rowIndex, booleanValue);
                return;
            }
            if (logicalType.equals(LogicalType.INTEGER)) {
                vector.setInteger(rowIndex, integerValue);
                return;
            }
            if (logicalType.equals(LogicalType.BIGINT)) {
                vector.setBigint(rowIndex, bigintValue);
                return;
            }
            if (logicalType.equals(LogicalType.DOUBLE)) {
                vector.setDouble(rowIndex, doubleValue);
                return;
            }
            if (logicalType.equals(LogicalType.TEXT)) {
                vector.setText(rowIndex, textValue);
                return;
            }
            if (logicalType.equals(LogicalType.DATE)) {
                vector.setDate(rowIndex, dateValue);
                return;
            }
            vector.setNull(rowIndex);
        }

        private int compareTo(Cell other) {
            if (isNumeric() && other.isNumeric()) {
                return Double.compare(numericAsDouble(), other.numericAsDouble());
            }
            if (logicalType.equals(LogicalType.BOOLEAN) && other.logicalType.equals(LogicalType.BOOLEAN)) {
                return Boolean.compare(booleanValue, other.booleanValue);
            }
            if (logicalType.equals(LogicalType.TEXT) && other.logicalType.equals(LogicalType.TEXT)) {
                return textValue.compareTo(other.textValue);
            }
            if (logicalType.equals(LogicalType.DATE) && other.logicalType.equals(LogicalType.DATE)) {
                return dateValue.compareTo(other.dateValue);
            }
            throw new ExecutionException("Cannot aggregate compare " + logicalType.id().name()
                    + " and " + other.logicalType.id().name());
        }

        private boolean isNumeric() {
            return logicalType.equals(LogicalType.INTEGER)
                    || logicalType.equals(LogicalType.BIGINT)
                    || logicalType.equals(LogicalType.DOUBLE);
        }

        private long numericAsLong() {
            if (logicalType.equals(LogicalType.INTEGER)) {
                return integerValue;
            }
            if (logicalType.equals(LogicalType.BIGINT)) {
                return bigintValue;
            }
            if (logicalType.equals(LogicalType.DOUBLE)) {
                return (long) doubleValue;
            }
            throw new ExecutionException("Aggregate function expects numeric input");
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
            throw new ExecutionException("Aggregate function expects numeric input");
        }
    }
}
