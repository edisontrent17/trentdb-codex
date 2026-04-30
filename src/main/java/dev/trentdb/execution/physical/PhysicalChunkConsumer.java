package dev.trentdb.execution.physical;

import dev.trentdb.common.vector.DataChunk;

@FunctionalInterface
public interface PhysicalChunkConsumer {
    void accept(DataChunk chunk);
}
