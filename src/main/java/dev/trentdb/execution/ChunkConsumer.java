package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;

interface ChunkConsumer {
    void accept(DataChunk chunk);
}
