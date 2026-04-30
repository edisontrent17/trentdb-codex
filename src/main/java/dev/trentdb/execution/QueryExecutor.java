package dev.trentdb.execution;

import dev.trentdb.execution.physical.PhysicalPlanner;
import dev.trentdb.execution.physical.PipelineExecutor;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.storage.StorageManager;

public final class QueryExecutor {
    private final StorageManager storageManager;

    public QueryExecutor(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public QueryResult execute(LogicalOperator operator) {
        var pipeline = new PhysicalPlanner(storageManager).plan(operator);
        new PipelineExecutor().execute(pipeline);
        return pipeline.sink().result();
    }
}
