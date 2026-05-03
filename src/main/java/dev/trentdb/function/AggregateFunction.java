package dev.trentdb.function;

import dev.trentdb.types.LogicalType;

public record AggregateFunction(String name, LogicalType returnType) {
    public AggregateFunction {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Aggregate function name must not be blank");
        }
    }
}
