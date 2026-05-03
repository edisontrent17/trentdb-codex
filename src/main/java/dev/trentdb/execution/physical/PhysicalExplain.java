package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalPlanPrinter;
import dev.trentdb.types.LogicalType;

import java.util.List;

public final class PhysicalExplain implements PhysicalSource {
    private final LogicalOperator logicalPlan;

    public PhysicalExplain(LogicalOperator logicalPlan) {
        this.logicalPlan = logicalPlan;
    }

    public LogicalOperator logicalPlan() {
        return logicalPlan;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.EXPLAIN;
    }

    @Override
    public void execute(PhysicalChunkConsumer consumer) {
        Vector vector = new Vector(LogicalType.TEXT, new Object[]{new LogicalPlanPrinter().print(logicalPlan)});
        consumer.accept(new DataChunk(List.of("explain"), List.of(vector)));
    }
}
