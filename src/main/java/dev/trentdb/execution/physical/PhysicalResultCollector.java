package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.QueryResult;

import java.util.ArrayList;
import java.util.List;

public final class PhysicalResultCollector implements PhysicalSink {
    private List<String> columns = List.of();
    private final List<List<Object>> rows = new ArrayList<>();

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.RESULT_COLLECTOR;
    }

    @Override
    public void sink(DataChunk chunk) {
        if (columns.isEmpty()) {
            columns = chunk.names();
        }
        for (int rowIndex = 0; rowIndex < chunk.cardinality(); rowIndex++) {
            ArrayList<Object> row = new ArrayList<>(chunk.vectors().size());
            for (Vector vector : chunk.vectors()) {
                row.add(vector.get(rowIndex));
            }
            rows.add(row);
        }
    }

    @Override
    public QueryResult result() {
        return new QueryResult(columns, rows);
    }
}
