package dev.trentdb.execution.physical;

import dev.trentdb.execution.ExecutionException;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.storage.StorageManager;

import java.util.ArrayList;

public final class PhysicalPlanner {
    private final StorageManager storageManager;

    public PhysicalPlanner(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public Pipeline plan(LogicalOperator logical) {
        var sink = new PhysicalResultCollector();
        if (logical instanceof LogicalExplain explain) {
            return new Pipeline(new PhysicalExplain(explain), java.util.List.of(), sink);
        }

        var operators = new ArrayList<PhysicalIntermediateOperator>();
        var source = buildPipeline(logical, operators);
        return new Pipeline(source, operators, sink);
    }

    private PhysicalSource buildPipeline(LogicalOperator logical, ArrayList<PhysicalIntermediateOperator> operators) {
        if (logical instanceof LogicalProjection projection) {
            var source = buildPipeline(projection.child(), operators);
            operators.add(new PhysicalProjection(projection.expressions(), projection.names()));
            return source;
        }
        if (logical instanceof LogicalFilter filter) {
            var source = buildPipeline(filter.child(), operators);
            operators.add(new PhysicalFilter(filter.predicate()));
            return source;
        }
        if (logical instanceof LogicalLimit limit) {
            var source = buildPipeline(limit.child(), operators);
            operators.add(new PhysicalLimit(limit.limit()));
            return source;
        }
        if (logical instanceof LogicalGet get) {
            return new PhysicalTableScan(storageManager, get.tableRef());
        }
        throw new ExecutionException("Unsupported logical operator for physical planning: " + logical.getClass().getSimpleName());
    }
}
