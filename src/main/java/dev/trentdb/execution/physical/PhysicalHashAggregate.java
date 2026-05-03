package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundAggregateExpression;
import dev.trentdb.planner.BoundBetweenExpression;
import dev.trentdb.planner.BoundBinaryExpression;
import dev.trentdb.planner.BoundColumnRefExpression;
import dev.trentdb.planner.BoundCastExpression;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.planner.BoundLiteralExpression;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class PhysicalHashAggregate implements PhysicalOperator {
    private final List<BoundExpression> groups;
    private final List<BoundExpression> selectList;
    private final List<String> selectNames;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalHashAggregate(List<BoundExpression> groups, List<BoundExpression> selectList, List<String> selectNames) {
        this.groups = List.copyOf(groups);
        this.selectList = List.copyOf(selectList);
        this.selectNames = List.copyOf(selectNames);
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new AggregateLocalState(selectList);
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        AggregateLocalState state = (AggregateLocalState) operatorInput.localState();
        List<Vector> groupVectors = groups.stream()
                .map(group -> expressionExecutor.execute(group, input))
                .toList();
        List<Vector> aggregateVectors = aggregateArgumentVectors(input);

        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            GroupKey key = groupKey(groupVectors, rowIndex);
            AggregateRow row = state.groups.computeIfAbsent(key, ignored -> new AggregateRow(key.values(), selectList));
            for (int selectIndex = 0; selectIndex < selectList.size(); selectIndex++) {
                if (selectList.get(selectIndex) instanceof BoundAggregateExpression aggregate) {
                    Vector argumentVector = aggregateVectors.get(selectIndex);
                    Object value = aggregate.starArgument() ? null : argumentVector.get(rowIndex);
                    row.states[selectIndex].update(value, aggregate.starArgument());
                }
            }
        }
    }

    @Override
    public void finish(OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        AggregateLocalState state = (AggregateLocalState) operatorInput.localState();
        if (groups.isEmpty() && state.groups.isEmpty()) {
            GroupKey key = new GroupKey(List.of());
            state.groups.put(key, new AggregateRow(key.values(), selectList));
        }
        ArrayList<AggregateRow> rows = new ArrayList<>(state.groups.values());
        for (int offset = 0; offset < rows.size(); offset += InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
            downstream.accept(chunk(rows, offset, Math.min(InMemoryTableStorage.STANDARD_VECTOR_SIZE, rows.size() - offset)));
        }
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        throw new UnsupportedOperationException("PhysicalHashAggregate requires operator state");
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
        ArrayList<Object> values = new ArrayList<>(groupVectors.size());
        for (Vector vector : groupVectors) {
            values.add(vector.get(rowIndex));
        }
        return new GroupKey(values);
    }

    private DataChunk chunk(List<AggregateRow> rows, int offset, int size) {
        ArrayList<Vector> vectors = new ArrayList<>(selectList.size());
        for (int selectIndex = 0; selectIndex < selectList.size(); selectIndex++) {
            BoundExpression expression = selectList.get(selectIndex);
            Vector vector = new Vector(logicalType(expression), size);
            for (int rowIndex = 0; rowIndex < size; rowIndex++) {
                vector.set(rowIndex, value(rows.get(offset + rowIndex), expression, selectIndex));
            }
            vectors.add(vector);
        }
        return new DataChunk(selectNames, vectors);
    }

    private Object value(AggregateRow row, BoundExpression expression, int selectIndex) {
        if (expression instanceof BoundAggregateExpression) {
            return row.states[selectIndex].result();
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
            case BoundCastExpression cast -> cast.logicalType();
            case BoundColumnRefExpression column -> column.logicalType();
            case BoundFunctionExpression function -> function.logicalType();
            case BoundLiteralExpression literal -> literal.logicalType();
        };
    }

    private record GroupKey(List<Object> values) {
        private GroupKey {
            values = List.copyOf(values);
        }
    }

    private static final class AggregateLocalState extends LocalOperatorState {
        private final LinkedHashMap<GroupKey, AggregateRow> groups = new LinkedHashMap<>();

        private AggregateLocalState(List<BoundExpression> selectList) {
            if (selectList.isEmpty()) {
                throw new IllegalArgumentException("Aggregate select list must not be empty");
            }
        }
    }

    private static final class AggregateRow {
        private final List<Object> groupValues;
        private final AggregateState[] states;

        private AggregateRow(List<Object> groupValues, List<BoundExpression> selectList) {
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
        private Object value;

        private AggregateState(BoundAggregateExpression aggregate) {
            this.name = aggregate.name().toLowerCase(java.util.Locale.ROOT);
            this.returnType = aggregate.logicalType();
        }

        private void update(Object input, boolean starArgument) {
            if (name.equals("count")) {
                if (starArgument || input != null) {
                    count++;
                }
                return;
            }
            if (input == null) {
                return;
            }
            switch (name) {
                case "sum" -> updateSum(input);
                case "avg" -> updateAverage(input);
                case "min" -> updateMin(input);
                case "max" -> updateMax(input);
                default -> throw new ExecutionException("Unsupported aggregate function: " + name);
            }
        }

        private Object result() {
            return switch (name) {
                case "count" -> count;
                case "sum" -> sumResult();
                case "avg" -> count == 0 ? null : doubleSum / count;
                case "min", "max" -> value;
                default -> throw new ExecutionException("Unsupported aggregate function: " + name);
            };
        }

        private void updateSum(Object input) {
            Number number = number(input);
            count++;
            if (returnType.equals(LogicalType.DOUBLE)) {
                doubleSum += number.doubleValue();
            } else {
                longSum += number.longValue();
            }
        }

        private void updateAverage(Object input) {
            Number number = number(input);
            count++;
            doubleSum += number.doubleValue();
        }

        private Object sumResult() {
            if (count == 0) {
                return null;
            }
            if (returnType.equals(LogicalType.DOUBLE)) {
                return doubleSum;
            }
            return longSum;
        }

        private void updateMin(Object input) {
            if (value == null || compare(input, value) < 0) {
                value = input;
            }
        }

        private void updateMax(Object input) {
            if (value == null || compare(input, value) > 0) {
                value = input;
            }
        }

        private Number number(Object input) {
            if (input instanceof Number number) {
                return number;
            }
            throw new ExecutionException("Aggregate function " + name + " expects numeric input");
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private int compare(Object left, Object right) {
            if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
                return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
            }
            if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
                return comparable.compareTo(right);
            }
            throw new ExecutionException("Cannot aggregate compare " + left.getClass().getSimpleName()
                    + " and " + right.getClass().getSimpleName());
        }
    }
}
