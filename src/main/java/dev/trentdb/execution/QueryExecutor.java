package dev.trentdb.execution;

import dev.trentdb.execution.physical.PhysicalPlanner;
import dev.trentdb.execution.physical.Pipeline;
import dev.trentdb.execution.physical.PipelineExecutor;
import dev.trentdb.optimizer.Optimizer;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.storage.StorageManager;

public final class QueryExecutor {
    private static final ThreadLocal<Integer> EXECUTION_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final StorageManager storageManager;

    public QueryExecutor(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public QueryResult execute(LogicalOperator operator) {
        int previousDepth = EXECUTION_DEPTH.get();
        int depth = previousDepth + 1;
        EXECUTION_DEPTH.set(depth);
        try {
            return executeAtDepth(operator, depth);
        } finally {
            if (previousDepth == 0) {
                EXECUTION_DEPTH.remove();
            } else {
                EXECUTION_DEPTH.set(previousDepth);
            }
        }
    }

    private QueryResult executeAtDepth(LogicalOperator operator, int depth) {
        long totalStart = ExecutionProfiler.start();
        long optimizeStart = ExecutionProfiler.start();
        Optimizer optimizer = new Optimizer(ExecutionProfiler.enabled());
        LogicalOperator optimized = optimizer.optimize(operator);
        String optimizerDetails = depthDetails(depth)
                + " input=" + operator.getClass().getSimpleName()
                + " output=" + optimized.getClass().getSimpleName();
        if (ExecutionProfiler.enabled()) {
            optimizerDetails = optimizerDetails + " " + optimizer.metrics().profileDetails();
        }
        ExecutionProfiler.log(
                "QueryExecutor",
                "optimize",
                optimizeStart,
                optimizerDetails
        );

        long planStart = ExecutionProfiler.start();
        Pipeline pipeline = new PhysicalPlanner(storageManager).plan(optimized);
        ExecutionProfiler.log(
                "QueryExecutor",
                "plan",
                planStart,
                depthDetails(depth) + " logical=" + optimized.getClass().getSimpleName()
        );

        long pipelineStart = ExecutionProfiler.start();
        new PipelineExecutor().execute(pipeline);
        ExecutionProfiler.log(
                "QueryExecutor",
                "pipeline",
                pipelineStart,
                depthDetails(depth)
                        + " source=" + pipeline.source().type().name()
                        + " operators=" + pipeline.operators().size()
        );

        long resultStart = ExecutionProfiler.start();
        QueryResult result = pipeline.sink().result();
        ExecutionProfiler.log(
                "QueryExecutor",
                "result",
                resultStart,
                depthDetails(depth)
                        + " rows=" + result.rows().size()
                        + " columns=" + result.columns().size()
        );
        ExecutionProfiler.log("QueryExecutor", "total", totalStart, depthDetails(depth));
        return result;
    }

    private static String depthDetails(int depth) {
        return "depth=" + depth;
    }
}
