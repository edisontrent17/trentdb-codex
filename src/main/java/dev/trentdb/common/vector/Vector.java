package dev.trentdb.common.vector;

import dev.trentdb.types.LogicalType;

import java.util.Arrays;

public final class Vector {
    private final LogicalType logicalType;
    private final VectorType vectorType;
    private final Object[] values;
    private final ValidityMask validity;
    private final Vector child;
    private final SelectionVector selection;
    private final int size;

    public Vector(LogicalType logicalType, int size) {
        this(logicalType, VectorType.FLAT, new Object[size], new ValidityMask(size), null, null, size);
    }

    public Vector(LogicalType logicalType, Object[] values) {
        this(logicalType, VectorType.FLAT, Arrays.copyOf(values, values.length), validityFor(values), null, null, values.length);
    }

    private Vector(
            LogicalType logicalType,
            VectorType vectorType,
            Object[] values,
            ValidityMask validity,
            Vector child,
            SelectionVector selection,
            int size
    ) {
        this.logicalType = logicalType;
        this.vectorType = vectorType;
        this.values = values;
        this.validity = validity;
        this.child = child;
        this.selection = selection;
        this.size = size;
    }

    public static Vector constant(LogicalType logicalType, Object value, int size) {
        var validity = new ValidityMask(1);
        validity.setValid(0, value != null);
        return new Vector(logicalType, VectorType.CONSTANT, new Object[]{value}, validity, null, null, size);
    }

    public static Vector dictionary(Vector child, SelectionVector selection, int size) {
        return new Vector(child.logicalType(), VectorType.DICTIONARY, null, null, child, selection.copy(size), size);
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
            case FLAT -> values[index];
            case CONSTANT -> values[0];
            case DICTIONARY -> child.get(selection.getIndex(index));
        };
    }

    public void set(int index, Object value) {
        if (vectorType != VectorType.FLAT) {
            throw new IllegalStateException("Only flat vectors can be mutated");
        }
        values[index] = value;
        validity.setValid(index, value != null);
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
        values[index] = null;
        validity.setValid(index, false);
    }

    public Vector slice(SelectionVector selection, int selectedCount) {
        return dictionary(this, selection, selectedCount);
    }

    private static ValidityMask validityFor(Object[] values) {
        var validity = new ValidityMask(values.length);
        for (int index = 0; index < values.length; index++) {
            validity.setValid(index, values[index] != null);
        }
        return validity;
    }
}
