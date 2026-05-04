package dev.trentdb.execution.physical;

import dev.trentdb.ast.SortDirection;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundOrderByItem;
import dev.trentdb.storage.InMemoryTableStorage;
import dev.trentdb.types.LogicalType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PhysicalOrder implements PhysicalOperator {
    private final List<BoundOrderByItem> orders;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalOrder(List<BoundOrderByItem> orders) {
        this.orders = List.copyOf(orders);
    }

    public List<BoundOrderByItem> orders() {
        return orders;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.ORDER_BY;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new OrderLocalState();
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        OrderLocalState state = (OrderLocalState) operatorInput.localState();
        state.captureSchema(input);
        int chunkIndex = state.inputChunks.size();
        state.inputChunks.add(input);

        List<Vector> orderVectors = orders.stream()
                .map(order -> expressionExecutor.execute(order.expression(), input))
                .toList();
        if (state.orderNames == null) {
            ArrayList<String> orderNames = new ArrayList<>(orderVectors.size());
            for (int index = 0; index < orderVectors.size(); index++) {
                orderNames.add("order_" + index);
            }
            state.orderNames = orderNames;
        }
        state.orderChunks.add(new DataChunk(state.orderNames, orderVectors));

        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            state.rows.add(new RowRef(chunkIndex, rowIndex));
        }
    }

    @Override
    public void finish(OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        OrderLocalState state = (OrderLocalState) operatorInput.localState();
        if (state.names != null && state.rows.isEmpty()) {
            downstream.accept(emptyChunk(state));
            return;
        }
        state.rows.sort(rowComparator(state));
        for (int offset = 0; offset < state.rows.size(); offset += InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
            int size = Math.min(InMemoryTableStorage.STANDARD_VECTOR_SIZE, state.rows.size() - offset);
            downstream.accept(chunk(state, offset, size));
        }
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        throw new UnsupportedOperationException("PhysicalOrder requires operator state");
    }

    private DataChunk chunk(OrderLocalState state, int offset, int size) {
        ArrayList<Vector> vectors = new ArrayList<>(state.types.size());
        for (LogicalType type : state.types) {
            vectors.add(new Vector(type, size));
        }

        for (int rowIndex = 0; rowIndex < size; rowIndex++) {
            RowRef rowRef = state.rows.get(offset + rowIndex);
            DataChunk sourceChunk = state.inputChunks.get(rowRef.chunkIndex());
            for (int columnIndex = 0; columnIndex < vectors.size(); columnIndex++) {
                vectors.get(columnIndex).copyFrom(rowIndex, sourceChunk.column(columnIndex), rowRef.rowIndex());
            }
        }
        return new DataChunk(state.names, vectors);
    }

    private DataChunk emptyChunk(OrderLocalState state) {
        List<Vector> vectors = state.types.stream()
                .map(type -> new Vector(type, 0))
                .toList();
        return new DataChunk(state.names, vectors);
    }

    private Comparator<RowRef> rowComparator(OrderLocalState state) {
        return (left, right) -> {
            for (int index = 0; index < orders.size(); index++) {
                DataChunk leftOrderChunk = state.orderChunks.get(left.chunkIndex());
                DataChunk rightOrderChunk = state.orderChunks.get(right.chunkIndex());
                int result = compareKey(
                        leftOrderChunk.column(index),
                        left.rowIndex(),
                        rightOrderChunk.column(index),
                        right.rowIndex(),
                        orders.get(index).direction()
                );
                if (result != 0) {
                    return result;
                }
            }
            return 0;
        };
    }

    private int compareKey(Vector left, int leftIndex, Vector right, int rightIndex, SortDirection direction) {
        if (left.isNull(leftIndex) && right.isNull(rightIndex)) {
            return 0;
        }
        if (left.isNull(leftIndex)) {
            return 1;
        }
        if (right.isNull(rightIndex)) {
            return -1;
        }

        int result = compareNonNull(left, leftIndex, right, rightIndex);
        return direction == SortDirection.ASC ? result : -result;
    }

    private int compareNonNull(Vector left, int leftIndex, Vector right, int rightIndex) {
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
        throw new ExecutionException("Cannot order values " + leftType.id().name() + " and " + rightType.id().name());
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
        throw new ExecutionException("Cannot order non-numeric type " + type.id().name());
    }

    private static final class OrderLocalState extends LocalOperatorState {
        private final List<RowRef> rows = new ArrayList<>();
        private final List<DataChunk> inputChunks = new ArrayList<>();
        private final List<DataChunk> orderChunks = new ArrayList<>();
        private List<String> names;
        private List<String> orderNames;
        private List<LogicalType> types;

        private void captureSchema(DataChunk input) {
            if (names != null) {
                return;
            }
            names = input.names();
            types = input.vectors().stream()
                    .map(Vector::logicalType)
                    .toList();
        }
    }

    private record RowRef(int chunkIndex, int rowIndex) {
    }
}
