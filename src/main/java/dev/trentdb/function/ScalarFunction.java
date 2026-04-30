package dev.trentdb.function;

import dev.trentdb.types.LogicalType;

import java.util.List;

public record ScalarFunction(String name, List<LogicalType> argumentTypes, LogicalType returnType) {
    public ScalarFunction {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Function name must not be blank");
        }
        argumentTypes = List.copyOf(argumentTypes);
    }

    public int argumentCount() {
        return argumentTypes.size();
    }
}
