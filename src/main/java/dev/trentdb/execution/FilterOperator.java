package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;
import dev.trentdb.planner.BoundExpression;

final class FilterOperator implements ChunkConsumer {
    private final BoundExpression predicate;
    private final ChunkConsumer downstream;
    private final ExpressionExecutor expressionExecutor = new ExpressionExecutor();

    FilterOperator(BoundExpression predicate, ChunkConsumer downstream) {
        this.predicate = predicate;
        this.downstream = downstream;
    }

    @Override
    public void accept(DataChunk chunk) {
        var predicateVector = expressionExecutor.execute(predicate, chunk);
        var selection = new SelectionVector(chunk.cardinality());
        int selectedCount = 0;
        for (int index = 0; index < chunk.cardinality(); index++) {
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
        if (selectedCount == chunk.cardinality()) {
            downstream.accept(chunk);
            return;
        }
        downstream.accept(chunk.slice(selection, selectedCount));
    }
}
