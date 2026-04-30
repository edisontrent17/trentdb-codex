package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundExpression;

public final class PhysicalFilter implements PhysicalIntermediateOperator {
    private final BoundExpression predicate;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    public PhysicalFilter(BoundExpression predicate) {
        this.predicate = predicate;
    }

    public BoundExpression predicate() {
        return predicate;
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        var predicateVector = expressionExecutor.execute(predicate, input);
        var selection = new SelectionVector(input.cardinality());
        int selectedCount = 0;
        for (int index = 0; index < input.cardinality(); index++) {
            if (predicateVector.isNull(index)) {
                continue;
            }
            var value = predicateVector.get(index);
            if (!(value instanceof Boolean booleanValue)) {
                throw new ExecutionException("Predicate did not evaluate to BOOLEAN");
            }
            if (booleanValue) {
                selection.setIndex(selectedCount, index);
                selectedCount++;
            }
        }
        if (selectedCount == input.cardinality()) {
            downstream.accept(input);
            return;
        }
        downstream.accept(input.slice(selection, selectedCount));
    }
}
