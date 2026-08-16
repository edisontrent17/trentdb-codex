package dev.trentdb.storage.wal;

/** Internal WAL record kinds; the encoding is not DuckDB WAL compatible. */
public enum WalRecordType {
    BEGIN(1), WRITE(2), COMMIT(3), ROLLBACK(4);

    private final int id;
    WalRecordType(int id) { this.id = id; }
    int id() { return id; }
    static WalRecordType fromId(int id) {
        for (var value : values()) if (value.id == id) return value;
        throw new WalException("Unknown WAL record type: " + id);
    }
}
