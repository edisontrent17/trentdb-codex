package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.logical.LogicalOperator;
import dev.trentdb.planner.logical.LogicalPlanPrinter;
import dev.trentdb.types.LogicalType;

import java.util.List;

public final class PhysicalExplain implements PhysicalSource {
    private final LogicalOperator logicalPlan;
    private final Pipeline physicalPlan;

    public PhysicalExplain(LogicalOperator logicalPlan, Pipeline physicalPlan) {
        this.logicalPlan = logicalPlan;
        this.physicalPlan = physicalPlan;
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
        Vector vector = new Vector(LogicalType.TEXT, 1);
        vector.setText(0, explainText());
        consumer.accept(new DataChunk(List.of("explain"), List.of(vector)));
    }

    private String explainText() {
        return "Logical Plan\n"
                + new LogicalPlanPrinter().print(logicalPlan)
                + "\n"
                + new PhysicalPlanPrinter().print(physicalPlan);
    }
}
