package dev.trentdb.storage.wal;

import java.util.Arrays;

/** A decoded WAL frame. */
public record WalRecord(long transactionId, long sequence, WalRecordType type, byte[] payload) {
    public WalRecord {
        if (transactionId <= 0 || sequence <= 0 || type == null || payload == null) {
            throw new IllegalArgumentException("Invalid WAL record");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }
    @Override public byte[] payload() { return Arrays.copyOf(payload, payload.length); }
}
