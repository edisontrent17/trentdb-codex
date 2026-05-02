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

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new OrderLocalState();
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        OrderLocalState state = (OrderLocalState) operatorInput.localState();
        state.captureSchema(input);

        List<Vector> orderVectors = orders.stream()
                .map(order -> expressionExecutor.execute(order.expression(), input))
                .toList();
        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            state.rows.add(captureRow(input, orderVectors, rowIndex));
        }
    }

    @Override
    public void finish(OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        OrderLocalState state = (OrderLocalState) operatorInput.localState();
        if (state.names != null && state.rows.isEmpty()) {
            downstream.accept(emptyChunk(state));
            return;
        }
        state.rows.sort(rowComparator());
        for (int offset = 0; offset < state.rows.size(); offset += InMemoryTableStorage.STANDARD_VECTOR_SIZE) {
            downstream.accept(chunk(state, offset, Math.min(InMemoryTableStorage.STANDARD_VECTOR_SIZE, state.rows.size() - offset)));
        }
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        throw new UnsupportedOperationException("PhysicalOrder requires operator state");
    }

    private OrderedRow captureRow(DataChunk input, List<Vector> orderVectors, int rowIndex) {
        Object[] values = new Object[input.vectors().size()];
        for (int columnIndex = 0; columnIndex < input.vectors().size(); columnIndex++) {
            values[columnIndex] = input.column(columnIndex).get(rowIndex);
        }

        Object[] keys = new Object[orderVectors.size()];
        for (int keyIndex = 0; keyIndex < orderVectors.size(); keyIndex++) {
            keys[keyIndex] = orderVectors.get(keyIndex).get(rowIndex);
        }
        return new OrderedRow(values, keys);
    }

    private DataChunk chunk(OrderLocalState state, int offset, int size) {
        ArrayList<Vector> vectors = new ArrayList<>(state.types.size());
        for (int columnIndex = 0; columnIndex < state.types.size(); columnIndex++) {
            Vector vector = new Vector(state.types.get(columnIndex), size);
            for (int rowIndex = 0; rowIndex < size; rowIndex++) {
                vector.set(rowIndex, state.rows.get(offset + rowIndex).values[columnIndex]);
            }
            vectors.add(vector);
        }
        return new DataChunk(state.names, vectors);
    }

    private DataChunk emptyChunk(OrderLocalState state) {
        List<Vector> vectors = state.types.stream()
                .map(type -> new Vector(type, 0))
                .toList();
        return new DataChunk(state.names, vectors);
    }

    private Comparator<OrderedRow> rowComparator() {
        return (left, right) -> {
            for (int index = 0; index < orders.size(); index++) {
                int result = compareKey(left.keys[index], right.keys[index], orders.get(index).direction());
                if (result != 0) {
                    return result;
                }
            }
            return 0;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareKey(Object left, Object right, SortDirection direction) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }

        int result;
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            result = Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        } else if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            result = comparable.compareTo(right);
        } else {
            throw new ExecutionException("Cannot order values " + left.getClass().getSimpleName()
                    + " and " + right.getClass().getSimpleName());
        }
        return direction == SortDirection.ASC ? result : -result;
    }

    private static final class OrderLocalState extends LocalOperatorState {
        private final List<OrderedRow> rows = new ArrayList<>();
        private List<String> names;
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

    private record OrderedRow(Object[] values, Object[] keys) {
    }
}
