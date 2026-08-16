package dev.trentdb.function;

import dev.trentdb.types.LogicalType;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FunctionRegistry {
    private final Map<String, ScalarFunction> scalarFunctions = new HashMap<>();
    private final Map<String, String> aggregateFunctions = new HashMap<>();

    public static FunctionRegistry withBuiltIns() {
        FunctionRegistry registry = new FunctionRegistry();
        registry.registerScalar(new ScalarFunction("lower", List.of(LogicalType.TEXT), LogicalType.TEXT));
        registry.registerScalar(new ScalarFunction("date_part", List.of(LogicalType.TEXT, LogicalType.DATE), LogicalType.BIGINT));
        registry.registerScalar(new ScalarFunction(
                "substring",
                List.of(LogicalType.TEXT, LogicalType.BIGINT, LogicalType.BIGINT),
                LogicalType.TEXT
        ));
        registry.registerAggregate("count");
        registry.registerAggregate("sum");
        registry.registerAggregate("min");
        registry.registerAggregate("max");
        registry.registerAggregate("avg");
        return registry;
    }

    public void registerScalar(ScalarFunction function) {
        String key = normalize(function.name());
        ScalarFunction previous = scalarFunctions.putIfAbsent(key, function);
        if (previous != null) {
            throw new FunctionException("Scalar function already exists: " + function.name());
        }
    }

    public ScalarFunction bindScalar(String name, List<LogicalType> argumentTypes) {
        ScalarFunction function = scalarFunctions.get(normalize(name));
        if (function == null) {
            if (normalize(name).equals("coalesce")) {
                return bindCoalesce(name, argumentTypes);
            }
            if (normalize(name).equals("abs")) {
                return bindAbs(name, argumentTypes);
            }
            throw new FunctionException("Scalar function not found: " + name);
        }
        if (argumentTypes.size() != function.argumentCount()) {
            throw new FunctionException("Scalar function " + name + " expects " + function.argumentCount()
                    + " arguments but got " + argumentTypes.size());
        }
        for (int index = 0; index < argumentTypes.size(); index++) {
            LogicalType actual = argumentTypes.get(index);
            LogicalType expected = function.argumentTypes().get(index);
            if (!actual.equals(expected)) {
                throw new FunctionException("Scalar function " + name + " argument " + (index + 1)
                        + " expects " + expected.id() + " but got " + actual.id());
            }
        }
        return function;
    }

    private ScalarFunction bindAbs(String name, List<LogicalType> argumentTypes) {
        if (argumentTypes.size() != 1) {
            throw new FunctionException("Scalar function " + name + " expects 1 argument but got " + argumentTypes.size());
        }
        LogicalType argumentType = argumentTypes.getFirst();
        if (!isNumeric(argumentType)) {
            throw new FunctionException("Scalar function " + name + " expects numeric input but got " + argumentType.id());
        }
        return new ScalarFunction("abs", List.of(argumentType), argumentType);
    }

    private ScalarFunction bindCoalesce(String name, List<LogicalType> argumentTypes) {
        if (argumentTypes.isEmpty()) {
            throw new FunctionException("Scalar function " + name + " expects at least 1 argument");
        }
        LogicalType result = LogicalType.NULL;
        for (LogicalType argumentType : argumentTypes) {
            result = commonCoalesceType(name, result, argumentType);
        }
        return new ScalarFunction("coalesce", argumentTypes, result);
    }

    private LogicalType commonCoalesceType(String name, LogicalType left, LogicalType right) {
        if (left.equals(LogicalType.NULL)) {
            return right;
        }
        if (right.equals(LogicalType.NULL) || left.equals(right)) {
            return left;
        }
        if (isNumeric(left) && isNumeric(right)) {
            return left.equals(LogicalType.DOUBLE) || right.equals(LogicalType.DOUBLE)
                    ? LogicalType.DOUBLE : LogicalType.BIGINT;
        }
        throw new FunctionException("Scalar function " + name + " arguments are incompatible: "
                + left.id() + " and " + right.id());
    }

    public boolean isAggregate(String name) {
        return aggregateFunctions.containsKey(normalize(name));
    }

    public AggregateFunction bindAggregate(String name, List<LogicalType> argumentTypes, boolean starArgument) {
        String normalized = normalize(name);
        if (!aggregateFunctions.containsKey(normalized)) {
            throw new FunctionException("Aggregate function not found: " + name);
        }
        return switch (normalized) {
            case "count" -> bindCount(name, argumentTypes, starArgument);
            case "sum" -> bindNumericAggregate(name, argumentTypes, starArgument, false);
            case "avg" -> bindNumericAggregate(name, argumentTypes, starArgument, true);
            case "min", "max" -> bindComparableAggregate(name, argumentTypes, starArgument);
            default -> throw new FunctionException("Aggregate function not found: " + name);
        };
    }

    private void registerAggregate(String name) {
        String key = normalize(name);
        String previous = aggregateFunctions.putIfAbsent(key, name);
        if (previous != null) {
            throw new FunctionException("Aggregate function already exists: " + name);
        }
    }

    private AggregateFunction bindCount(String name, List<LogicalType> argumentTypes, boolean starArgument) {
        if (starArgument) {
            if (!argumentTypes.isEmpty()) {
                throw new FunctionException("Aggregate function " + name + "(*) does not accept explicit arguments");
            }
            return new AggregateFunction(name, LogicalType.BIGINT);
        }
        if (argumentTypes.size() != 1) {
            throw new FunctionException("Aggregate function " + name + " expects 1 argument but got " + argumentTypes.size());
        }
        return new AggregateFunction(name, LogicalType.BIGINT);
    }

    private AggregateFunction bindNumericAggregate(String name, List<LogicalType> argumentTypes, boolean starArgument, boolean forceDouble) {
        rejectStarArgument(name, starArgument);
        if (argumentTypes.size() != 1) {
            throw new FunctionException("Aggregate function " + name + " expects 1 argument but got " + argumentTypes.size());
        }
        LogicalType argumentType = argumentTypes.getFirst();
        if (!isNumeric(argumentType)) {
            throw new FunctionException("Aggregate function " + name + " expects numeric input but got " + argumentType.id());
        }
        LogicalType returnType = forceDouble || argumentType.equals(LogicalType.DOUBLE) ? LogicalType.DOUBLE : LogicalType.BIGINT;
        return new AggregateFunction(name, returnType);
    }

    private AggregateFunction bindComparableAggregate(String name, List<LogicalType> argumentTypes, boolean starArgument) {
        rejectStarArgument(name, starArgument);
        if (argumentTypes.size() != 1) {
            throw new FunctionException("Aggregate function " + name + " expects 1 argument but got " + argumentTypes.size());
        }
        return new AggregateFunction(name, argumentTypes.getFirst());
    }

    private void rejectStarArgument(String name, boolean starArgument) {
        if (starArgument) {
            throw new FunctionException("Aggregate function " + name + " does not accept *");
        }
    }

    private boolean isNumeric(LogicalType logicalType) {
        return logicalType.equals(LogicalType.INTEGER)
                || logicalType.equals(LogicalType.BIGINT)
                || logicalType.equals(LogicalType.DOUBLE);
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
