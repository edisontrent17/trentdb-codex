package dev.trentdb.execution.physical;

public record OperatorInput(
        GlobalOperatorState globalState,
        LocalOperatorState localState
) {
}
