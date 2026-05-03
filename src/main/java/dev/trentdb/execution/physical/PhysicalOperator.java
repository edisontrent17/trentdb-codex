package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.QueryResult;

public interface PhysicalOperator {
    PhysicalOperatorType type();

    default GlobalOperatorState createGlobalOperatorState() {
        return new GlobalOperatorState();
    }

    default LocalOperatorState createLocalOperatorState(GlobalOperatorState globalState) {
        return new LocalOperatorState();
    }

    default void execute(DataChunk input, OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
        execute(input, downstream);
    }

    default void finish(OperatorInput operatorInput, PhysicalChunkConsumer downstream) {
    }

    default void execute(DataChunk input, PhysicalChunkConsumer downstream) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is not a regular pipeline operator");
    }

    default GlobalSourceState createGlobalSourceState() {
        return new GlobalSourceState();
    }

    default LocalSourceState createLocalSourceState(GlobalSourceState globalState) {
        return new LocalSourceState();
    }

    default void execute(SourceInput input, PhysicalChunkConsumer consumer) {
        execute(consumer);
    }

    default void execute(PhysicalChunkConsumer consumer) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is not a source operator");
    }

    default GlobalSinkState createGlobalSinkState() {
        return new GlobalSinkState();
    }

    default LocalSinkState createLocalSinkState(GlobalSinkState globalState) {
        return new LocalSinkState();
    }

    default void sink(DataChunk chunk, SinkInput input) {
        sink(chunk);
    }

    default void combine(GlobalSinkState globalState, LocalSinkState localState) {
    }

    default void sink(DataChunk chunk) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is not a sink operator");
    }

    default QueryResult result() {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is not a sink operator");
    }
}
