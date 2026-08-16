package dev.trentdb.sqllogic;

/** Terminal C2 disposition for every parsed command in the selected slice. */
public enum SqlLogicC2Outcome {
    PASS,
    ENGINE_FAILURE,
    RUNNER_UNSUPPORTED,
    ENVIRONMENT_BLOCKED
}
