package dev.trentdb.sqllogic;

import dev.trentdb.execution.QueryResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** DuckDB SQLLogic value, row-sort, value-sort, hash-threshold, and label comparison rules. */
final class SqlLogicResultComparator {
    private static final Pattern HASH = Pattern.compile("[0-9]+ values hashing to [0-9a-f]{32}");

    Comparison compare(SqlLogicQuery query, QueryResult result, int hashThreshold, Map<String, String> labels) {
        if (result.columns().size() != query.columnTypes().length()) {
            return Comparison.failure("expected " + query.columnTypes().length() + " columns but got " + result.columns().size());
        }
        List<String> actual = values(result, query.columnTypes());
        String sort = query.sortOrConnection() == null ? "nosort" : query.sortOrConnection().toLowerCase(Locale.ROOT);
        if (!sort.equals("nosort") && !sort.equals("rowsort") && !sort.equals("valuesort")) {
            return Comparison.unsupported("named connections and unknown query sort modes are not implemented: " + sort);
        }
        sort(actual, result.columns().size(), sort);
        boolean useHash = query.label() != null || (hashThreshold > 0 && actual.size() > hashThreshold) || isHashResult(query.expectedResults());
        if (useHash) {
            String actualHash = hash(actual);
            if (query.label() != null) {
                String previous = labels.putIfAbsent(query.label(), actualHash);
                if (previous != null && !previous.equals(actualHash)) {
                    return Comparison.failure("label " + query.label() + " changed from " + previous + " to " + actualHash);
                }
            }
            if (isHashResult(query.expectedResults()) && !query.expectedResults().getFirst().equals(actualHash)) {
                return Comparison.failure("expected " + query.expectedResults().getFirst() + " but got " + actualHash);
            }
            return Comparison.success();
        }
        List<String> expected = flattenExpected(query.expectedResults(), result.columns().size());
        if (expected.size() != actual.size()) {
            return Comparison.failure("expected " + expected.size() + " values but got " + actual.size());
        }
        for (int index = 0; index < actual.size(); index++) {
            if (!actual.get(index).equals(expected.get(index))) {
                return Comparison.failure("value " + index + ": expected " + expected.get(index) + " but got " + actual.get(index));
            }
        }
        return Comparison.success();
    }

    static String hash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return values.size() + " values hashing to " + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("MD5 is required by the Java runtime", exception);
        }
    }

    private static boolean isHashResult(List<String> expected) {
        return expected.size() == 1 && HASH.matcher(expected.getFirst()).matches();
    }

    private static List<String> values(QueryResult result, String columnTypes) {
        var values = new ArrayList<String>();
        for (List<Object> row : result.rows()) {
            for (int column = 0; column < row.size(); column++) {
                values.add(value(row.get(column), columnTypes.charAt(column)));
            }
        }
        return values;
    }

    private static String value(Object value, char expectedType) {
        if (value == null) {
            return "NULL";
        }
        if (expectedType != 'I') {
            return String.valueOf(value);
        }
        if (!(value instanceof Number number)) {
            return "<non-integer:" + value + ">";
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || Math.rint(decimal) != decimal
                || decimal < Long.MIN_VALUE || decimal > Long.MAX_VALUE) {
            return "<non-integer:" + value + ">";
        }
        return Long.toString(number.longValue());
    }

    private static List<String> flattenExpected(List<String> expected, int columns) {
        if (columns > 1 && expected.stream().allMatch(value -> value.contains("\t"))) {
            var flattened = new ArrayList<String>();
            for (String row : expected) {
                String[] split = row.split("\t", -1);
                if (split.length != columns) {
                    return List.of("<invalid row-wise result>");
                }
                java.util.Collections.addAll(flattened, split);
            }
            return flattened;
        }
        return List.copyOf(expected);
    }

    private static void sort(List<String> values, int columns, String style) {
        if (style.equals("nosort")) {
            return;
        }
        if (style.equals("valuesort")) {
            values.sort(Comparator.naturalOrder());
            return;
        }
        var rows = new ArrayList<List<String>>();
        for (int index = 0; index < values.size(); index += columns) {
            rows.add(new ArrayList<>(values.subList(index, index + columns)));
        }
        rows.sort((left, right) -> {
            for (int index = 0; index < left.size(); index++) {
                int comparison = left.get(index).compareTo(right.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        });
        values.clear();
        rows.forEach(values::addAll);
    }

    record Comparison(SqlLogicC2Outcome outcome, String detail) {
        static Comparison success() {
            return new Comparison(SqlLogicC2Outcome.PASS, "query result matched");
        }

        static Comparison failure(String detail) {
            return new Comparison(SqlLogicC2Outcome.ENGINE_FAILURE, detail);
        }

        static Comparison unsupported(String detail) {
            return new Comparison(SqlLogicC2Outcome.RUNNER_UNSUPPORTED, detail);
        }
    }
}
