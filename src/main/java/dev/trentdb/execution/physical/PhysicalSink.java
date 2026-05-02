package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.execution.QueryResult;

public interface PhysicalSink extends PhysicalOperator {
    @Override
    void sink(DataChunk chunk);

    @Override
    QueryResult result();
}
