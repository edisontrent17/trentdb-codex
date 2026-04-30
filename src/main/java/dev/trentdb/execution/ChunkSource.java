package dev.trentdb.execution;

interface ChunkSource {
    void execute(ChunkConsumer consumer);
}
