package dev.trentdb.storage;

/** The table checkpoint entry immediately after a decoded CreateTableInfo prefix. */
public record DuckDbTableEntryEnvelope(
        DuckDbTableCreateInfo createInfo,
        MetaPointer tablePointer,
        long totalRows,
        long nextRowId,
        Boundary boundary) {
    public DuckDbTableEntryEnvelope {
        if (createInfo == null || tablePointer == null || boundary == null) throw new IllegalArgumentException();
    }
    public record MetaPointer(long packedBlockPointer, long offset) {
        public long blockId() { return packedBlockPointer & 0x00ff_ffff_ffff_ffffL; }
        public int subBlockIndex() { return (int) (packedBlockPointer >>> 56); }
    }
    public enum Boundary { TABLE_METADATA_CHAIN_ROW_GROUPS_AND_INDEXES_UNSUPPORTED }
}
