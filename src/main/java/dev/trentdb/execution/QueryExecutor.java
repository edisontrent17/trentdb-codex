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
        long totalStart = ExecutionProfiler.start();
        long planStart = ExecutionProfiler.start();
        dev.trentdb.execution.physical.Pipeline pipeline = new PhysicalPlanner(storageManager).plan(operator);
        ExecutionProfiler.log(
                "QueryExecutor",
                "plan",
                planStart,
                "logical=" + operator.getClass().getSimpleName()
        );

        long pipelineStart = ExecutionProfiler.start();
        new PipelineExecutor().execute(pipeline);
        ExecutionProfiler.log(
                "QueryExecutor",
                "pipeline",
                pipelineStart,
                "source=" + pipeline.source().type().name() + " operators=" + pipeline.operators().size()
        );

        long resultStart = ExecutionProfiler.start();
        QueryResult result = pipeline.sink().result();
        ExecutionProfiler.log(
                "QueryExecutor",
                "result",
                resultStart,
                "rows=" + result.rows().size() + " columns=" + result.columns().size()
        );
        ExecutionProfiler.log("QueryExecutor", "total", totalStart, null);
        return result;
    }
}
