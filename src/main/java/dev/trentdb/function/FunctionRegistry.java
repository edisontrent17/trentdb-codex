package dev.trentdb.function;

import dev.trentdb.types.LogicalType;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FunctionRegistry {
    private final Map<String, ScalarFunction> scalarFunctions = new HashMap<>();

    public static FunctionRegistry withBuiltIns() {
        var registry = new FunctionRegistry();
        registry.registerScalar(new ScalarFunction("lower", List.of(LogicalType.TEXT), LogicalType.TEXT));
        return registry;
    }

    public void registerScalar(ScalarFunction function) {
        var key = normalize(function.name());
        var previous = scalarFunctions.putIfAbsent(key, function);
        if (previous != null) {
            throw new FunctionException("Scalar function already exists: " + function.name());
        }
    }

    public ScalarFunction bindScalar(String name, List<LogicalType> argumentTypes) {
        var function = scalarFunctions.get(normalize(name));
        if (function == null) {
            throw new FunctionException("Scalar function not found: " + name);
        }
        if (argumentTypes.size() != function.argumentCount()) {
            throw new FunctionException("Scalar function " + name + " expects " + function.argumentCount()
                    + " arguments but got " + argumentTypes.size());
        }
        for (int index = 0; index < argumentTypes.size(); index++) {
            var actual = argumentTypes.get(index);
            var expected = function.argumentTypes().get(index);
            if (!actual.equals(expected)) {
                throw new FunctionException("Scalar function " + name + " argument " + (index + 1)
                        + " expects " + expected.id() + " but got " + actual.id());
            }
        }
        return function;
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
