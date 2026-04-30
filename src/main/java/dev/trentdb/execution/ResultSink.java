package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;

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
            var row = new ArrayList<>(chunk.vectors().size());
            for (var vector : chunk.vectors()) {
                row.add(vector.get(rowIndex));
            }
            rows.add(row);
        }
    }

    QueryResult result() {
        return new QueryResult(columns, rows);
    }
}
