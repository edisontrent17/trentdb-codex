package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;
class DuckDbTableMetadataWriterTest {
    @TempDir
    Path directory;

    @Test
    void emitsExactBinaryFieldUlebAndTerminatorBytes() {
        Path path = directory.resolve("golden.duckdb");
        MetaBlockPointer pointer;
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16]);
             MetadataChainWriter chain = new MetadataChainWriter(manager)) {
            DuckDbBinaryMetadataWriter writer = new DuckDbBinaryMetadataWriter(chain);
            pointer = writer.currentPointer();
            writer.beginObject();
            writer.beginProperty(100);
            writer.writeUnsignedLeb128(300);
            writer.endObject();
            chain.flush();
            assertArrayEquals(new byte[] {-1, -1, -1, -1, -1, -1, -1, -1, 100, 0, -84, 2, -1, -1},
                    Arrays.copyOf(manager.readBlock(pointer.blockId()), 14));
        }
    }

    @Test
    void roundTripsPrimitiveColumnAndMultiSubBlockRowGroups() {
        Path path = directory.resolve("roundtrip.duckdb");
        DuckDbTableEntryEnvelope.MetaPointer columnPointer;
        MetaBlockPointer rowGroupPointer;
        DuckDbPrimitiveColumnMetadata expectedColumn;
        DuckDbRowGroupHeaders expectedRows;
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16])) {
            DuckDbPrimitivePayloadWriter.EncodedVector vector = new DuckDbPrimitivePayloadWriter(manager).write(
                    DuckDbTableCreateInfo.ScalarLogicalType.BIGINT, Arrays.asList(-9L, null, 7L));
            expectedColumn = new DuckDbPrimitiveColumnMetadata(DuckDbTableCreateInfo.ScalarLogicalType.BIGINT,
                    List.of(vector.dataSegment()), List.of(vector.validitySegment().orElseThrow()),
                    DuckDbPrimitiveColumnMetadata.Boundary.BLOCK_PAYLOAD_DECOMPRESSION_UNSUPPORTED);
            DuckDbTableStatistics statistics = new DuckDbTableStatistics(List.of(
                    new DuckDbTableStatistics.Primitive(true, true, 0, DuckDbTableStatistics.Kind.BIGINT,
                            OptionalLong.of(-9), OptionalLong.of(7))));
            try (DuckDbTableMetadataWriter writer = new DuckDbTableMetadataWriter(manager)) {
                columnPointer = writer.writePrimitiveColumnMetadata(expectedColumn);
                List<DuckDbRowGroupHeaders.Header> groups = new java.util.ArrayList<>();
                for (int index = 0; index < 240; index++) {
                    groups.add(new DuckDbRowGroupHeaders.Header(index * 3L, 3,
                            List.of(columnPointer), List.of(), false, List.of(), false, List.of()));
                }
                expectedRows = new DuckDbRowGroupHeaders(statistics, groups);
                rowGroupPointer = writer.writeRowGroups(expectedRows);
            }
            assertEquals(3, manager.activeHeader().blockCount());
        }

        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            assertEquals(expectedColumn, new DuckDbPrimitiveColumnMetadataReader(manager, columnPointer,
                    DuckDbTableCreateInfo.ScalarLogicalType.BIGINT).read());
            DuckDbRowGroupHeaders actual = new DuckDbRowGroupHeaderReader(manager, rowGroupPointer,
                    List.of(DuckDbTableCreateInfo.ScalarLogicalType.BIGINT)).read();
            assertEquals(expectedRows, actual);
            assertEquals(240, actual.groups().size());
        }
    }

    @Test
    void serializesOptionalV2RowGroupFieldsAndRejectsInvalidStatistics() {
        Path path = directory.resolve("optional.duckdb");
        MetaBlockPointer rowGroupPointer;
        DuckDbTableStatistics statistics = new DuckDbTableStatistics(List.of(
                new DuckDbTableStatistics.Primitive(false, true, 0, DuckDbTableStatistics.Kind.BOOLEAN,
                        OptionalLong.of(0), OptionalLong.of(1))));
        DuckDbRowGroupHeaders.Header header = new DuckDbRowGroupHeaders.Header(10, 2,
                List.of(new DuckDbTableEntryEnvelope.MetaPointer(0, 0)), List.of(), true, List.of(6L), true,
                List.of(new DuckDbRowGroupHeaders.PerColumnMetadataBlock(true, 4)));
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16]);
             DuckDbTableMetadataWriter writer = new DuckDbTableMetadataWriter(manager)) {
            rowGroupPointer = writer.writeRowGroups(new DuckDbRowGroupHeaders(statistics, List.of(header)));
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            DuckDbRowGroupHeaders.Header actual = new DuckDbRowGroupHeaderReader(manager, rowGroupPointer,
                    List.of(DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN)).read().groups().getFirst();
            assertTrue(actual.hasPerColumnMetadataBlocks());
            assertFalse(actual.hasMetadataBlocks());
            assertEquals(List.of(), actual.extraMetadataBlocks());
            assertEquals(List.of(new DuckDbRowGroupHeaders.PerColumnMetadataBlock(true, 4)),
                    actual.perColumnMetadataBlocks());
        }
    }
}
