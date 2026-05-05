package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.execution.ExecutionException;
import dev.trentdb.execution.ExpressionExecutor;
import dev.trentdb.planner.BoundExpression;
import dev.trentdb.storage.StorageManager;
import dev.trentdb.types.LogicalType;

public final class PhysicalFilter implements PhysicalOperator {
    private final BoundExpression predicate;
    private final ExpressionExecutor expressionExecutor;

    public PhysicalFilter(BoundExpression predicate) {
        this(predicate, null);
    }

    public PhysicalFilter(BoundExpression predicate, StorageManager storageManager) {
        this.predicate = predicate;
        this.expressionExecutor = new ExpressionExecutor(storageManager);
    }

    public BoundExpression predicate() {
        return predicate;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.FILTER;
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        Vector predicateVector = expressionExecutor.execute(predicate, input);
        SelectionVector selection = new SelectionVector(input.cardinality());
        int selectedCount = 0;
        for (int index = 0; index < input.cardinality(); index++) {
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
        if (selectedCount == input.cardinality()) {
            downstream.accept(input);
            return;
        }
        downstream.accept(input.slice(selection, selectedCount));
    }
}
