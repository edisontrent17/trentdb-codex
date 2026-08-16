package dev.trentdb.execution.physical;

import dev.trentdb.ast.SetOperation;
import dev.trentdb.common.VectorSize;
import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.QueryResult;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Blocking physical source for a binary SQL set operation.  It deliberately
 * materializes its already-planned children so duplicate handling happens at
 * the set boundary, rather than being lost in either input pipeline.
 */
public final class PhysicalSetOperation implements PhysicalSource {
    private final SetOperation operation;
    private final Pipeline left;
    private final Pipeline right;
    private final List<String> names;
    private final List<LogicalType> types;

    public PhysicalSetOperation(
            SetOperation operation,
            Pipeline left,
            Pipeline right,
            List<String> names,
            List<LogicalType> types
    ) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.names = List.copyOf(names);
        this.types = List.copyOf(types);
        if (this.names.size() != this.types.size()) {
            throw new IllegalArgumentException("Set-operation names and types must have the same size");
        }
    }

    @Override
    public PhysicalOperatorType type() {
        return switch (operation) {
            case UNION -> PhysicalOperatorType.UNION;
            case UNION_ALL -> PhysicalOperatorType.UNION_ALL;
            case EXCEPT -> PhysicalOperatorType.EXCEPT;
            case INTERSECT -> PhysicalOperatorType.INTERSECT;
        };
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        QueryResult leftResult = execute(left);
        QueryResult rightResult = execute(right);
        List<List<Object>> rows = rows(leftResult.rows(), rightResult.rows());
        emit(rows, consumer);
    }

    private QueryResult execute(Pipeline pipeline) {
        new PipelineExecutor().execute(pipeline);
        return pipeline.sink().result();
    }

    private List<List<Object>> rows(List<List<Object>> leftRows, List<List<Object>> rightRows) {
        if (operation == SetOperation.UNION_ALL) {
            ArrayList<List<Object>> rows = new ArrayList<>(leftRows.size() + rightRows.size());
            rows.addAll(leftRows);
            rows.addAll(rightRows);
            return rows;
        }

        Set<Row> leftSet = rowsAsSet(leftRows);
        Set<Row> rightSet = rowsAsSet(rightRows);
        LinkedHashSet<Row> result = new LinkedHashSet<>();
        switch (operation) {
            case UNION -> {
                result.addAll(leftSet);
                result.addAll(rightSet);
            }
            case EXCEPT -> {
                result.addAll(leftSet);
                result.removeAll(rightSet);
            }
            case INTERSECT -> {
                for (Row row : leftSet) {
                    if (rightSet.contains(row)) {
                        result.add(row);
                    }
                }
            }
            case UNION_ALL -> throw new IllegalStateException("handled above");
        }
        return result.stream().map(Row::values).toList();
    }

    private Set<Row> rowsAsSet(List<List<Object>> rows) {
        LinkedHashSet<Row> result = new LinkedHashSet<>();
        for (List<Object> row : rows) {
            if (row.size() != types.size()) {
                throw new IllegalStateException("Set-operation input column count does not match output schema");
            }
            result.add(new Row(row));
        }
        return result;
    }

    private void emit(List<List<Object>> rows, PhysicalChunkConsumer consumer) {
        if (rows.isEmpty()) {
            ArrayList<Vector> vectors = new ArrayList<>(types.size());
            for (LogicalType type : types) {
                vectors.add(new Vector(type, 0));
            }
            consumer.accept(new DataChunk(names, vectors));
            return;
        }
        for (int offset = 0; offset < rows.size(); offset += VectorSize.STANDARD_VECTOR_SIZE) {
            int count = Math.min(VectorSize.STANDARD_VECTOR_SIZE, rows.size() - offset);
            ArrayList<Vector> vectors = new ArrayList<>(types.size());
            for (int column = 0; column < types.size(); column++) {
                Vector vector = new Vector(types.get(column), count);
                for (int row = 0; row < count; row++) {
                    set(vector, row, rows.get(offset + row).get(column));
                }
                vectors.add(vector);
            }
            consumer.accept(new DataChunk(names, vectors));
        }
    }

    private void set(Vector vector, int index, Object value) {
        if (value == null) {
            vector.setNull(index);
            return;
        }
        switch (vector.logicalType().id()) {
            case BOOLEAN -> vector.setBoolean(index, (Boolean) value);
            case INTEGER -> vector.setInteger(index, ((Number) value).intValue());
            case BIGINT -> vector.setBigint(index, ((Number) value).longValue());
            case DOUBLE -> vector.setDouble(index, ((Number) value).doubleValue());
            case TEXT -> vector.setText(index, (String) value);
            case DATE -> vector.setDate(index, (LocalDate) value);
            case INTERVAL, NULL -> vector.setNull(index);
        }
    }

    private record Row(List<Object> values) {
        private Row {
            // SQL NULL is a valid set-membership value. List.copyOf rejects
            // nulls, whereas an unmodifiable ArrayList keeps tuple equality
            // and hashing null-tolerant.
            values = java.util.Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
