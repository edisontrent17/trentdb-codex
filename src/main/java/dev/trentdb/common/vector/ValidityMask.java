package dev.trentdb.common.vector;

import java.util.Arrays;

public final class ValidityMask {
    private final boolean[] valid;

    public ValidityMask(int size) {
        this.valid = new boolean[size];
        Arrays.fill(valid, true);
    }

    private ValidityMask(boolean[] valid) {
        this.valid = Arrays.copyOf(valid, valid.length);
    }

    public boolean isValid(int index) {
        return valid[index];
    }

    public void setValid(int index, boolean isValid) {
        valid[index] = isValid;
    }

    public ValidityMask copy() {
        return new ValidityMask(valid);
    }
}
