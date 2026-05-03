package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;

import java.util.ArrayList;
import java.util.List;

final class ResultSink implements ChunkConsumer {
    private List<String> columns = List.of();
    private final List<List<Object>> rows = new ArrayList<>();

    @Override
    public void accept(DataChunk chunk) {
        if (columns.isEmpty()) {
            columns = chunk.names();
        }
        for (int rowIndex = 0; rowIndex < chunk.cardinality(); rowIndex++) {
            ArrayList<Object> row = new ArrayList<>(chunk.vectors().size());
            for (Vector vector : chunk.vectors()) {
                row.add(vector.boxedValue(rowIndex));
            }
            rows.add(row);
        }
    }

    QueryResult result() {
        return new QueryResult(columns, rows);
    }
}
