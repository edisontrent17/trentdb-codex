package dev.trentdb.execution.physical;

public record SourceInput(
        GlobalSourceState globalState,
        LocalSourceState localState
) {
}
