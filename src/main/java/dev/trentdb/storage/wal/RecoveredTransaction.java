package dev.trentdb.storage.wal;

import java.util.Arrays;
import java.util.List;

/** A committed transaction selected by WAL recovery. */
public record RecoveredTransaction(long transactionId, long commitVersion, List<byte[]> writes) {
    public RecoveredTransaction {
        writes = writes.stream().map(value -> Arrays.copyOf(value, value.length)).toList();
    }
    @Override public List<byte[]> writes() {
        return writes.stream().map(value -> Arrays.copyOf(value, value.length)).toList();
    }
}
