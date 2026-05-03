package dev.trentdb.common.vector;

import dev.trentdb.types.LogicalType;

import java.time.LocalDate;

public final class Vector {
    private record VectorStorage(
            boolean[] booleans,
            int[] integers,
            long[] bigints,
            double[] doubles,
            String[] texts,
            LocalDate[] dates
    ) {
    }

    private final LogicalType logicalType;
    private final VectorType vectorType;
    private final ValidityMask validity;
    private final Vector child;
    private final SelectionVector selection;
    private final int size;
    private final boolean[] booleans;
    private final int[] integers;
    private final long[] bigints;
    private final double[] doubles;
    private final String[] texts;
    private final LocalDate[] dates;

    public Vector(LogicalType logicalType, int size) {
        this(
                logicalType,
                VectorType.FLAT,
                defaultValidity(logicalType, size),
                null,
                null,
                size,
                allocateStorage(logicalType, size)
        );
    }

    private Vector(
            LogicalType logicalType,
            VectorType vectorType,
            ValidityMask validity,
            Vector child,
            SelectionVector selection,
            int size,
            VectorStorage storage
    ) {
        this.logicalType = logicalType;
        this.vectorType = vectorType;
        this.validity = validity;
        this.child = child;
        this.selection = selection;
        this.size = size;
        this.booleans = storage.booleans();
        this.integers = storage.integers();
        this.bigints = storage.bigints();
        this.doubles = storage.doubles();
        this.texts = storage.texts();
        this.dates = storage.dates();
    }

    public static Vector constant(LogicalType logicalType, Object value, int size) {
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, value != null);
        Vector vector = new Vector(
                logicalType,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(logicalType, 1)
        );
        if (value != null) {
            vector.writeNonNullValue(0, value);
        }
        return vector;
    }

    public static Vector dictionary(Vector child, SelectionVector selection, int size) {
        return new Vector(
                child.logicalType(),
                VectorType.DICTIONARY,
                null,
                child,
                selection.copy(size),
                size,
                allocateStorage(child.logicalType(), 0)
        );
    }

    public LogicalType logicalType() {
        return logicalType;
    }

    public VectorType vectorType() {
        return vectorType;
    }

    public int size() {
        return size;
    }

    public Object get(int index) {
        if (isNull(index)) {
            return null;
        }
        return switch (vectorType) {
            case FLAT -> getFlatValue(index);
            case CONSTANT -> getFlatValue(0);
            case DICTIONARY -> child.get(selection.getIndex(index));
        };
    }

    public void set(int index, Object value) {
        if (vectorType != VectorType.FLAT) {
            throw new IllegalStateException("Only flat vectors can be mutated");
        }
        if (value == null) {
            setNull(index);
            return;
        }
        writeNonNullValue(index, value);
        validity.setValid(index, true);
    }

    public boolean isNull(int index) {
        return switch (vectorType) {
            case FLAT -> !validity.isValid(index);
            case CONSTANT -> !validity.isValid(0);
            case DICTIONARY -> child.isNull(selection.getIndex(index));
        };
    }

    public void setNull(int index) {
        if (vectorType != VectorType.FLAT) {
            throw new IllegalStateException("Only flat vectors can be mutated");
        }
        clearValue(index);
        validity.setValid(index, false);
    }

    public Vector slice(SelectionVector selection, int selectedCount) {
        return dictionary(this, selection, selectedCount);
    }

    private Object getFlatValue(int index) {
        return switch (logicalType.id()) {
            case BOOLEAN -> booleans[index];
            case INTEGER -> integers[index];
            case BIGINT -> bigints[index];
            case DOUBLE -> doubles[index];
            case TEXT -> texts[index];
            case DATE -> dates[index];
            case NULL -> null;
        };
    }

    private void writeNonNullValue(int index, Object value) {
        switch (logicalType.id()) {
            case BOOLEAN -> {
                if (value instanceof Boolean bool) {
                    booleans[index] = bool;
                    return;
                }
                throw typeMismatch(value, "BOOLEAN");
            }
            case INTEGER -> {
                if (value instanceof Number number) {
                    integers[index] = number.intValue();
                    return;
                }
                throw typeMismatch(value, "INTEGER");
            }
            case BIGINT -> {
                if (value instanceof Number number) {
                    bigints[index] = number.longValue();
                    return;
                }
                throw typeMismatch(value, "BIGINT");
            }
            case DOUBLE -> {
                if (value instanceof Number number) {
                    doubles[index] = number.doubleValue();
                    return;
                }
                throw typeMismatch(value, "DOUBLE");
            }
            case TEXT -> {
                if (value instanceof String text) {
                    texts[index] = text;
                    return;
                }
                throw typeMismatch(value, "TEXT");
            }
            case DATE -> {
                if (value instanceof LocalDate date) {
                    dates[index] = date;
                    return;
                }
                throw typeMismatch(value, "DATE");
            }
            case NULL -> throw new IllegalArgumentException("NULL vector cannot store non-null values");
        }
    }

    private void clearValue(int index) {
        switch (logicalType.id()) {
            case BOOLEAN -> booleans[index] = false;
            case INTEGER -> integers[index] = 0;
            case BIGINT -> bigints[index] = 0L;
            case DOUBLE -> doubles[index] = 0.0d;
            case TEXT -> texts[index] = null;
            case DATE -> dates[index] = null;
            case NULL -> {
                return;
            }
        }
    }

    private IllegalArgumentException typeMismatch(Object value, String expected) {
        return new IllegalArgumentException(
                "Expected " + expected + " value for vector type " + logicalType.id().name() + " but got "
                        + value.getClass().getSimpleName()
        );
    }

    private static VectorStorage allocateStorage(LogicalType logicalType, int size) {
        return switch (logicalType.id()) {
            case BOOLEAN -> new VectorStorage(new boolean[size], null, null, null, null, null);
            case INTEGER -> new VectorStorage(null, new int[size], null, null, null, null);
            case BIGINT -> new VectorStorage(null, null, new long[size], null, null, null);
            case DOUBLE -> new VectorStorage(null, null, null, new double[size], null, null);
            case TEXT -> new VectorStorage(null, null, null, null, new String[size], null);
            case DATE -> new VectorStorage(null, null, null, null, null, new LocalDate[size]);
            case NULL -> new VectorStorage(null, null, null, null, null, null);
        };
    }

    private static ValidityMask defaultValidity(LogicalType logicalType, int size) {
        ValidityMask validity = new ValidityMask(size);
        if (logicalType.equals(LogicalType.NULL)) {
            for (int index = 0; index < size; index++) {
                validity.setValid(index, false);
            }
        }
        return validity;
    }

    public boolean getBoolean(int index) {
        ensureType(LogicalType.BOOLEAN, "BOOLEAN");
        int valueIndex = resolvedIndex(index);
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return booleans[valueIndex];
    }

    public void setBoolean(int index, boolean value) {
        ensureFlat();
        ensureType(LogicalType.BOOLEAN, "BOOLEAN");
        booleans[index] = value;
        validity.setValid(index, true);
    }

    public long getBigint(int index) {
        ensureType(LogicalType.BIGINT, "BIGINT");
        int valueIndex = resolvedIndex(index);
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return bigints[valueIndex];
    }

    public void setBigint(int index, long value) {
        ensureFlat();
        ensureType(LogicalType.BIGINT, "BIGINT");
        bigints[index] = value;
        validity.setValid(index, true);
    }

    public double getDouble(int index) {
        ensureType(LogicalType.DOUBLE, "DOUBLE");
        int valueIndex = resolvedIndex(index);
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return doubles[valueIndex];
    }

    public void setDouble(int index, double value) {
        ensureFlat();
        ensureType(LogicalType.DOUBLE, "DOUBLE");
        doubles[index] = value;
        validity.setValid(index, true);
    }

    public String getText(int index) {
        ensureType(LogicalType.TEXT, "TEXT");
        int valueIndex = resolvedIndex(index);
        if (isNull(index)) {
            return null;
        }
        return texts[valueIndex];
    }

    public void setText(int index, String value) {
        ensureFlat();
        ensureType(LogicalType.TEXT, "TEXT");
        if (value == null) {
            setNull(index);
            return;
        }
        texts[index] = value;
        validity.setValid(index, true);
    }

    private int resolvedIndex(int index) {
        return switch (vectorType) {
            case FLAT -> index;
            case CONSTANT -> 0;
            case DICTIONARY -> selection.getIndex(index);
        };
    }

    private void ensureFlat() {
        if (vectorType != VectorType.FLAT) {
            throw new IllegalStateException("Only flat vectors can be mutated");
        }
    }

    private void ensureType(LogicalType expectedType, String expectedName) {
        if (!logicalType.equals(expectedType)) {
            throw new IllegalStateException("Vector type is " + logicalType.id().name() + " not " + expectedName);
        }
    }
}
