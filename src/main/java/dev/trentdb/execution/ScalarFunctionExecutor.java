package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.Locale;

final class ScalarFunctionExecutor {
    private final ExpressionExecutor expressionExecutor;

    ScalarFunctionExecutor(ExpressionExecutor expressionExecutor) {
        this.expressionExecutor = expressionExecutor;
    }

    Vector execute(BoundFunctionExpression function, DataChunk input) {
        if (function.name().equalsIgnoreCase("lower")) {
            return lower(function, input);
        }
        if (function.name().equalsIgnoreCase("date_part")) {
            return datePart(function, input);
        }
        throw new ExecutionException("Unsupported scalar function: " + function.name());
    }

    private Vector lower(BoundFunctionExpression function, DataChunk input) {
        Vector argument = expressionExecutor.execute(function.arguments().getFirst(), input);
        if (!argument.logicalType().equals(LogicalType.TEXT)) {
            throw new ExecutionException("lower expects TEXT input");
        }
        Vector result = new Vector(function.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            if (argument.isNull(index)) {
                result.setNull(index);
                continue;
            }
            result.setText(index, argument.getText(index).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private Vector datePart(BoundFunctionExpression function, DataChunk input) {
        Vector field = expressionExecutor.execute(function.arguments().get(0), input);
        Vector dates = expressionExecutor.execute(function.arguments().get(1), input);
        if (!field.logicalType().equals(LogicalType.TEXT) || !dates.logicalType().equals(LogicalType.DATE)) {
            throw new ExecutionException("date_part expects TEXT and DATE inputs");
        }
        Vector result = new Vector(function.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            if (field.isNull(index) || dates.isNull(index)) {
                result.setNull(index);
                continue;
            }
            writeDatePart(result, index, field.getText(index), dates.getDate(index));
        }
        return result;
    }

    private void writeDatePart(Vector result, int index, String field, LocalDate date) {
        switch (field.toLowerCase(Locale.ROOT)) {
            case "year" -> result.setBigint(index, date.getYear());
            case "month" -> result.setBigint(index, date.getMonthValue());
            case "day" -> result.setBigint(index, date.getDayOfMonth());
            default -> throw new ExecutionException("Unsupported date_part field: " + field);
        }
    }
}
