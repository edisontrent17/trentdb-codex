package dev.trentdb.execution.physical;

public record SinkInput(
        GlobalSinkState globalState,
        LocalSinkState localState
) {
}
