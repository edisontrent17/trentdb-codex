package dev.trentdb.execution.physical;

public interface PhysicalSource extends PhysicalOperator {
    @Override
    void execute(PhysicalChunkConsumer consumer);
}
