package dev.trentdb.execution.physical;

public non-sealed interface PhysicalSource extends PhysicalOperator {
    default GlobalSourceState createGlobalSourceState() {
        return new GlobalSourceState();
    }

    default LocalSourceState createLocalSourceState(GlobalSourceState globalState) {
        return new LocalSourceState();
    }

    default void execute(SourceInput input, PhysicalChunkConsumer consumer) {
        execute(consumer);
    }

    void execute(PhysicalChunkConsumer consumer);
}
