package dev.trentdb.execution;

import dev.trentdb.common.vector.DataChunk;
import dev.trentdb.common.vector.Vector;
import dev.trentdb.planner.BoundFunctionExpression;
import dev.trentdb.types.LogicalType;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

final class ScalarFunctionExecutor {
    private final ExpressionExecutor expressionExecutor;

    ScalarFunctionExecutor(ExpressionExecutor expressionExecutor) {
        this.expressionExecutor = expressionExecutor;
    }

    Vector execute(BoundFunctionExpression function, DataChunk input) {
        if (function.name().equalsIgnoreCase("coalesce")) {
            return coalesce(function, input);
        }
        if (function.name().equalsIgnoreCase("abs")) {
            return abs(function, input);
        }
        if (function.name().equalsIgnoreCase("lower")) {
            return lower(function, input);
        }
        if (function.name().equalsIgnoreCase("date_part")) {
            return datePart(function, input);
        }
        if (function.name().equalsIgnoreCase("substring")) {
            return substring(function, input);
        }
        throw new ExecutionException("Unsupported scalar function: " + function.name());
    }

    private Vector coalesce(BoundFunctionExpression function, DataChunk input) {
        if (function.arguments().isEmpty()) {
            throw new ExecutionException("coalesce expects at least one argument");
        }
        if (function.logicalType().equals(LogicalType.NULL)) {
            return Vector.constantNull(LogicalType.NULL, input.cardinality());
        }
        List<Vector> arguments = function.arguments().stream()
                .map(argument -> expressionExecutor.execute(argument, input))
                .toList();
        Vector result = new Vector(function.logicalType(), input.cardinality());
        for (int rowIndex = 0; rowIndex < input.cardinality(); rowIndex++) {
            boolean found = false;
            for (Vector argument : arguments) {
                if (argument.isNull(rowIndex)) {
                    continue;
                }
                writeCoalesceValue(result, rowIndex, argument, rowIndex);
                found = true;
                break;
            }
            if (!found) {
                result.setNull(rowIndex);
            }
        }
        return result;
    }

    private void writeCoalesceValue(Vector target, int targetIndex, Vector source, int sourceIndex) {
        if (target.logicalType().equals(source.logicalType())) {
            target.copyFrom(targetIndex, source, sourceIndex);
            return;
        }
        if (target.logicalType().equals(LogicalType.DOUBLE)) {
            if (source.logicalType().equals(LogicalType.INTEGER)) {
                target.setDouble(targetIndex, source.getInteger(sourceIndex));
                return;
            }
            if (source.logicalType().equals(LogicalType.BIGINT)) {
                target.setDouble(targetIndex, source.getBigint(sourceIndex));
                return;
            }
        }
        if (target.logicalType().equals(LogicalType.BIGINT) && source.logicalType().equals(LogicalType.INTEGER)) {
            target.setBigint(targetIndex, source.getInteger(sourceIndex));
            return;
        }
        throw new ExecutionException("Cannot coalesce " + source.logicalType().id().name()
                + " into " + target.logicalType().id().name());
    }

    private Vector abs(BoundFunctionExpression function, DataChunk input) {
        Vector argument = expressionExecutor.execute(function.arguments().getFirst(), input);
        LogicalType type = argument.logicalType();
        if (!type.equals(LogicalType.INTEGER) && !type.equals(LogicalType.BIGINT) && !type.equals(LogicalType.DOUBLE)) {
            throw new ExecutionException("abs expects numeric input");
        }
        Vector result = new Vector(function.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            if (argument.isNull(index)) {
                result.setNull(index);
                continue;
            }
            if (type.equals(LogicalType.INTEGER)) {
                int value = argument.getInteger(index);
                if (value == Integer.MIN_VALUE) {
                    throw new ExecutionException("abs cannot represent the absolute value of INTEGER minimum");
                }
                result.setInteger(index, Math.abs(value));
            } else if (type.equals(LogicalType.BIGINT)) {
                long value = argument.getBigint(index);
                if (value == Long.MIN_VALUE) {
                    throw new ExecutionException("abs cannot represent the absolute value of BIGINT minimum");
                }
                result.setBigint(index, Math.abs(value));
            } else {
                result.setDouble(index, Math.abs(argument.getDouble(index)));
            }
        }
        return result;
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

    private Vector substring(BoundFunctionExpression function, DataChunk input) {
        Vector text = expressionExecutor.execute(function.arguments().get(0), input);
        Vector start = expressionExecutor.execute(function.arguments().get(1), input);
        Vector length = expressionExecutor.execute(function.arguments().get(2), input);
        if (!text.logicalType().equals(LogicalType.TEXT)
                || !start.logicalType().equals(LogicalType.BIGINT)
                || !length.logicalType().equals(LogicalType.BIGINT)) {
            throw new ExecutionException("substring expects TEXT, BIGINT, BIGINT inputs");
        }
        Vector result = new Vector(function.logicalType(), input.cardinality());
        for (int index = 0; index < input.cardinality(); index++) {
            if (text.isNull(index) || start.isNull(index) || length.isNull(index)) {
                result.setNull(index);
                continue;
            }
            result.setText(index, substring(text.getText(index), start.getBigint(index), length.getBigint(index)));
        }
        return result;
    }

    private String substring(String text, long start, long length) {
        long firstPosition = start < 0 ? text.length() + start + 1 : start;
        long beginPosition = length < 0 ? firstPosition + length : Math.max(1, firstPosition);
        long endPosition = length < 0 ? firstPosition - 1 : firstPosition + length - 1;
        if (endPosition < beginPosition || endPosition < 1 || beginPosition > text.length()) {
            return "";
        }
        int begin = (int) Math.max(0, beginPosition - 1);
        int end = (int) Math.min(text.length(), endPosition);
        return text.substring(begin, end);
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
