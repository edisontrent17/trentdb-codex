package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.types.LogicalType;

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
        dev.trentdb.common.vector.Vector predicateVector = expressionExecutor.execute(predicate, chunk);
        SelectionVector selection = new SelectionVector(chunk.cardinality());
        int selectedCount = 0;
        for (int index = 0; index < chunk.cardinality(); index++) {
            if (predicateVector.isNull(index)) {
                continue;
            }
            if (!predicateVector.logicalType().equals(LogicalType.BOOLEAN)) {
                throw new ExecutionException("Predicate did not evaluate to BOOLEAN");
            }
            if (predicateVector.getBoolean(index)) {
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
