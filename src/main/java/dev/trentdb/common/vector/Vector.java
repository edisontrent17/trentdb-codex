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

    public static Vector constantNull(LogicalType logicalType, int size) {
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, false);
        return new Vector(
                logicalType,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(logicalType, 1)
        );
    }

    public static Vector constantBoolean(Boolean value, int size) {
        if (value == null) {
            return constantNull(LogicalType.BOOLEAN, size);
        }
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, true);
        Vector vector = new Vector(
                LogicalType.BOOLEAN,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(LogicalType.BOOLEAN, 1)
        );
        vector.booleans[0] = value;
        return vector;
    }

    public static Vector constantInteger(Integer value, int size) {
        if (value == null) {
            return constantNull(LogicalType.INTEGER, size);
        }
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, true);
        Vector vector = new Vector(
                LogicalType.INTEGER,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(LogicalType.INTEGER, 1)
        );
        vector.integers[0] = value;
        return vector;
    }

    public static Vector constantBigint(Long value, int size) {
        if (value == null) {
            return constantNull(LogicalType.BIGINT, size);
        }
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, true);
        Vector vector = new Vector(
                LogicalType.BIGINT,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(LogicalType.BIGINT, 1)
        );
        vector.bigints[0] = value;
        return vector;
    }

    public static Vector constantDouble(Double value, int size) {
        if (value == null) {
            return constantNull(LogicalType.DOUBLE, size);
        }
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, true);
        Vector vector = new Vector(
                LogicalType.DOUBLE,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(LogicalType.DOUBLE, 1)
        );
        vector.doubles[0] = value;
        return vector;
    }

    public static Vector constantText(String value, int size) {
        if (value == null) {
            return constantNull(LogicalType.TEXT, size);
        }
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, true);
        Vector vector = new Vector(
                LogicalType.TEXT,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(LogicalType.TEXT, 1)
        );
        vector.texts[0] = value;
        return vector;
    }

    public static Vector constantDate(LocalDate value, int size) {
        if (value == null) {
            return constantNull(LogicalType.DATE, size);
        }
        ValidityMask validity = new ValidityMask(1);
        validity.setValid(0, true);
        Vector vector = new Vector(
                LogicalType.DATE,
                VectorType.CONSTANT,
                validity,
                null,
                null,
                size,
                allocateStorage(LogicalType.DATE, 1)
        );
        vector.dates[0] = value;
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

    private void clearValue(int index) {
        switch (logicalType.id()) {
            case BOOLEAN -> booleans[index] = false;
            case INTEGER -> integers[index] = 0;
            case BIGINT -> bigints[index] = 0L;
            case DOUBLE -> doubles[index] = 0.0d;
            case TEXT -> texts[index] = null;
            case DATE -> dates[index] = null;
            case INTERVAL -> {
                return;
            }
            case NULL -> {
                return;
            }
        }
    }

    private static VectorStorage allocateStorage(LogicalType logicalType, int size) {
        return switch (logicalType.id()) {
            case BOOLEAN -> new VectorStorage(new boolean[size], null, null, null, null, null);
            case INTEGER -> new VectorStorage(null, new int[size], null, null, null, null);
            case BIGINT -> new VectorStorage(null, null, new long[size], null, null, null);
            case DOUBLE -> new VectorStorage(null, null, null, new double[size], null, null);
            case TEXT -> new VectorStorage(null, null, null, null, new String[size], null);
            case DATE -> new VectorStorage(null, null, null, null, null, new LocalDate[size]);
            case INTERVAL -> new VectorStorage(null, null, null, null, null, null);
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
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return switch (vectorType) {
            case FLAT -> booleans[index];
            case CONSTANT -> booleans[0];
            case DICTIONARY -> child.getBoolean(selection.getIndex(index));
        };
    }

    public void setBoolean(int index, boolean value) {
        ensureFlat();
        ensureType(LogicalType.BOOLEAN, "BOOLEAN");
        booleans[index] = value;
        validity.setValid(index, true);
    }

    public int getInteger(int index) {
        ensureType(LogicalType.INTEGER, "INTEGER");
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return switch (vectorType) {
            case FLAT -> integers[index];
            case CONSTANT -> integers[0];
            case DICTIONARY -> child.getInteger(selection.getIndex(index));
        };
    }

    public void setInteger(int index, int value) {
        ensureFlat();
        ensureType(LogicalType.INTEGER, "INTEGER");
        integers[index] = value;
        validity.setValid(index, true);
    }

    public long getBigint(int index) {
        ensureType(LogicalType.BIGINT, "BIGINT");
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return switch (vectorType) {
            case FLAT -> bigints[index];
            case CONSTANT -> bigints[0];
            case DICTIONARY -> child.getBigint(selection.getIndex(index));
        };
    }

    public void setBigint(int index, long value) {
        ensureFlat();
        ensureType(LogicalType.BIGINT, "BIGINT");
        bigints[index] = value;
        validity.setValid(index, true);
    }

    public double getDouble(int index) {
        ensureType(LogicalType.DOUBLE, "DOUBLE");
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return switch (vectorType) {
            case FLAT -> doubles[index];
            case CONSTANT -> doubles[0];
            case DICTIONARY -> child.getDouble(selection.getIndex(index));
        };
    }

    public void setDouble(int index, double value) {
        ensureFlat();
        ensureType(LogicalType.DOUBLE, "DOUBLE");
        doubles[index] = value;
        validity.setValid(index, true);
    }

    public String getText(int index) {
        ensureType(LogicalType.TEXT, "TEXT");
        if (isNull(index)) {
            return null;
        }
        return switch (vectorType) {
            case FLAT -> texts[index];
            case CONSTANT -> texts[0];
            case DICTIONARY -> child.getText(selection.getIndex(index));
        };
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

    public LocalDate getDate(int index) {
        ensureType(LogicalType.DATE, "DATE");
        if (isNull(index)) {
            throw new IllegalStateException("Value at index " + index + " is NULL");
        }
        return switch (vectorType) {
            case FLAT -> dates[index];
            case CONSTANT -> dates[0];
            case DICTIONARY -> child.getDate(selection.getIndex(index));
        };
    }

    public void setDate(int index, LocalDate value) {
        ensureFlat();
        ensureType(LogicalType.DATE, "DATE");
        if (value == null) {
            setNull(index);
            return;
        }
        dates[index] = value;
        validity.setValid(index, true);
    }

    public void copyFrom(int targetIndex, Vector source, int sourceIndex) {
        ensureFlat();
        ensureSameType(source);
        if (source.isNull(sourceIndex)) {
            setNull(targetIndex);
            return;
        }
        switch (logicalType.id()) {
            case BOOLEAN -> setBoolean(targetIndex, source.getBoolean(sourceIndex));
            case INTEGER -> setInteger(targetIndex, source.getInteger(sourceIndex));
            case BIGINT -> setBigint(targetIndex, source.getBigint(sourceIndex));
            case DOUBLE -> setDouble(targetIndex, source.getDouble(sourceIndex));
            case TEXT -> setText(targetIndex, source.getText(sourceIndex));
            case DATE -> setDate(targetIndex, source.getDate(sourceIndex));
            case INTERVAL -> setNull(targetIndex);
            case NULL -> setNull(targetIndex);
        }
    }

    public Object boxedValue(int index) {
        if (isNull(index)) {
            return null;
        }
        return switch (logicalType.id()) {
            case BOOLEAN -> getBoolean(index);
            case INTEGER -> getInteger(index);
            case BIGINT -> getBigint(index);
            case DOUBLE -> getDouble(index);
            case TEXT -> getText(index);
            case DATE -> getDate(index);
            case INTERVAL -> null;
            case NULL -> null;
        };
    }

    private void ensureSameType(Vector source) {
        if (!logicalType.equals(source.logicalType())) {
            throw new IllegalStateException("Vector type mismatch: " + logicalType.id().name()
                    + " vs " + source.logicalType().id().name());
        }
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
