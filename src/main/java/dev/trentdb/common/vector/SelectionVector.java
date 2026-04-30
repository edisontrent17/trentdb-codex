package dev.trentdb.common.vector;

import java.util.Arrays;

public final class SelectionVector {
    private final int[] indexes;
    private int count;

    public SelectionVector(int capacity) {
        this.indexes = new int[capacity];
    }

    public void setIndex(int index, int selectedIndex) {
        indexes[index] = selectedIndex;
        if (index >= count) {
            count = index + 1;
        }
    }

    public int getIndex(int index) {
        return indexes[index];
    }

    public int count() {
        return count;
    }

    public SelectionVector copy(int selectedCount) {
        var result = new SelectionVector(selectedCount);
        for (int index = 0; index < selectedCount; index++) {
            result.setIndex(index, indexes[index]);
        }
        return result;
    }

    @Override
    public String toString() {
        return Arrays.toString(Arrays.copyOf(indexes, count));
    }
}
