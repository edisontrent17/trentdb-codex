package dev.trentdb.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record QueryResult(List<String> columns, List<List<Object>> rows) {
    public QueryResult {
        columns = List.copyOf(columns);
        rows = rows.stream()
                .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                .toList();
    }
}
