package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.SelectionVector;

public final class PhysicalLimit implements PhysicalOperator {
    private final long limit;

    public PhysicalLimit(long limit) {
        this.limit = limit;
    }

    @Override
    public PhysicalOperatorType type() {
        return PhysicalOperatorType.LIMIT;
    }

    @Override
    public LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new LimitLocalState();
    }

    @Override
    public void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        LimitLocalState state = (LimitLocalState) operatorInput.localState();
        if (state.emitted >= limit) {
            if (!state.forwardedSchema) {
                state.forwardedSchema = true;
                downstream.accept(input.slice(new SelectionVector(0), 0));
            }
            return;
        }

        long remaining = limit - state.emitted;
        if (input.cardinality() <= remaining) {
            state.emitted += input.cardinality();
            state.forwardedSchema = true;
            downstream.accept(input);
            return;
        }

        int selectedCount = Math.toIntExact(remaining);
        SelectionVector selection = new SelectionVector(selectedCount);
        for (int index = 0; index < selectedCount; index++) {
            selection.setIndex(index, index);
        }
        state.emitted = limit;
        state.forwardedSchema = true;
        downstream.accept(input.slice(selection, selectedCount));
    }

    @Override
    public void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        throw new UnsupportedOperationException("PhysicalLimit requires operator state");
    }

    private static final class LimitLocalState extends LocalOperatorState {
        private long emitted;
        private boolean forwardedSchema;
    }
}
