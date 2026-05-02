package dev.trentdb.execution.physical;

import dev.trentdb.execution.ExecutionException;
import dev.trentdb.planner.logical.LogicalExplain;
import dev.trentdb.planner.logical.LogicalFilter;
import dev.trentdb.planner.logical.LogicalGet;
import dev.trentdb.planner.logical.LogicalLimit;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalOrder;
import dev.trentdb.planner.logical.LogicalProjection;
import dev.trentdb.storage.StorageManager;

import java.util.ArrayList;

public final class PhysicalPlanner {
    private final StorageManager storageManager;

    public PhysicalPlanner(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public Pipeline plan(LogicalOperator logical) {
        PhysicalResultCollector sink = new PhysicalResultCollector();
        if (logical instanceof LogicalExplain explain) {
            return new Pipeline(new PhysicalExplain(explain), java.util.List.of(), sink);
        }

        ArrayList<PhysicalOperator> operators = new ArrayList<>();
        PhysicalSource source = buildPipeline(logical, operators);
        return new Pipeline(source, operators, sink);
    }

    private PhysicalSource buildPipeline(LogicalOperator logical, ArrayList<PhysicalOperator> operators) {
        if (logical instanceof LogicalProjection projection) {
            PhysicalSource source = buildPipeline(projection.child(), operators);
            operators.add(new PhysicalProjection(projection.expressions(), projection.names()));
            return source;
        }
        if (logical instanceof LogicalFilter filter) {
            PhysicalSource source = buildPipeline(filter.child(), operators);
            operators.add(new PhysicalFilter(filter.predicate()));
            return source;
        }
        if (logical instanceof LogicalLimit limit) {
            PhysicalSource source = buildPipeline(limit.child(), operators);
            operators.add(new PhysicalLimit(limit.limit()));
            return source;
        }
        if (logical instanceof LogicalOrder order) {
            PhysicalSource source = buildPipeline(order.child(), operators);
            operators.add(new PhysicalOrder(order.orders()));
            return source;
        }
        if (logical instanceof LogicalGet get) {
            return new PhysicalTableScan(storageManager, get.tableRef());
        }
        throw new ExecutionException("Unsupported logical operator for physical planning: " + logical.getClass().getSimpleName());
    }
}
