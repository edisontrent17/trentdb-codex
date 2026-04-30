package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;

public non-sealed interface PhysicalIntermediateOperator extends PhysicalOperator {
    default GlobalOperatorState createGlobalOperatorState() {
        return new GlobalOperatorState();
    }

    default LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new LocalOperatorState();
    }

    default void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        execute(input, downstream);
    }

    void execute(DataChunk input, PhysicalChunkConsumer downstream);
}
