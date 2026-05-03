package dev.trentdb.execution.physical;

public enum PhysicalOperatorType {
    TABLE_SCAN,
    NESTED_LOOP_JOIN,
    FILTER,
    PROJECTION,
    LIMIT,
    ORDER_BY,
    HASH_GROUP_BY,
    EXPLAIN,
    RESULT_COLLECTOR
}
