package dev.trentdb.execution.physical;

public enum PhysicalOperatorType {
    TABLE_SCAN,
    HASH_JOIN,
    MARK_JOIN,
    SINGLE_JOIN,
    NESTED_LOOP_JOIN,
    FILTER,
    PROJECTION,
    LIMIT,
    ORDER_BY,
    HASH_GROUP_BY,
    EXPLAIN,
    CREATE_TABLE,
    DROP_TABLE,
    CREATE_INDEX,
    DROP_INDEX,
    INSERT,
    DELETE,
    UPDATE,
    RESULT_COLLECTOR,
    UNION,
    UNION_ALL,
    EXCEPT,
    INTERSECT
}
