package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.QueryResult;

public non-sealed interface PhysicalSink extends PhysicalOperator {
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

    void sink(DataChunk chunk);

    QueryResult result();
}
