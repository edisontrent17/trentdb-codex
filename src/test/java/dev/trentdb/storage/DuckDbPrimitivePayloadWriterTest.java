package dev.trentdb.storage;

import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbPrimitivePayloadWriterTest {
    @TempDir
    Path directory;

    @Test
    void writesLittleEndianPayloadLsbValidityAndStatistics() {
        Path path = directory.resolve("integer.duckdb");
        DuckDbPrimitivePayloadWriter.EncodedVector encoded;
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16])) {
            encoded = new DuckDbPrimitivePayloadWriter(manager).write(
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, Arrays.asList(2L, null, -7L, 258L));
            assertEquals(2, manager.activeHeader().blockCount());
            assertArrayEquals(new byte[] {2, 0, 0, 0, 0, 0, 0, 0, -7, -1, -1, -1, 2, 1, 0, 0},
                    Arrays.copyOf(manager.readBlock(0), 16));
            assertTrue(encoded.validitySegment().isPresent());
            byte[] validity = manager.readBlock(1);
            assertEquals(0b11111101, Byte.toUnsignedInt(validity[0]));
            assertEquals(0xFF, Byte.toUnsignedInt(validity[255]));
        }

        assertTrue(encoded.dataSegment().statistics().hasNull());
        assertTrue(encoded.dataSegment().statistics().hasNoNull());
        assertEquals(-7, encoded.dataSegment().statistics().min().getAsLong());
        assertEquals(258, encoded.dataSegment().statistics().max().getAsLong());
        assertEquals(0, encoded.dataSegment().statistics().distinctCount());
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            assertEquals(Arrays.asList(2L, null, -7L, 258L), new DuckDbPrimitivePayloadReader(manager).read(
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, encoded.dataSegment(),
                    encoded.validitySegment().orElseThrow()));
        }
    }

    @Test
    void roundTripsTwoVectorsThroughTheVectorizedScan() {
        Path path = directory.resolve("scan.duckdb");
        DuckDbPrimitivePayloadWriter.EncodedVector first;
        DuckDbPrimitivePayloadWriter.EncodedVector second;
        List<Long> values = new ArrayList<>();
        for (long value = 0; value < DuckDbPrimitivePayloadWriter.VECTOR_SIZE; value++) {
            values.add(value);
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(path, new byte[16])) {
            DuckDbPrimitivePayloadWriter writer = new DuckDbPrimitivePayloadWriter(manager);
            first = writer.write(DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, values);
            second = writer.write(DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, List.of(2048L));
            assertFalse(first.validitySegment().isPresent());
            assertFalse(second.validitySegment().isPresent());
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(path)) {
            DuckDbPrimitiveSegmentScan scan = new DuckDbPrimitiveSegmentScan(manager,
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER,
                    List.of(first.dataSegment(), second.dataSegment()), List.of(), 2049);
            DuckDbPrimitiveSegmentScan.Vector firstVector = scan.next();
            DuckDbPrimitiveSegmentScan.Vector secondVector = scan.next();
            assertEquals(0, firstVector.rowStart());
            assertEquals(2048, firstVector.values().size());
            assertEquals(2047L, firstVector.values().get(2047));
            assertEquals(2048, secondVector.rowStart());
            assertEquals(List.of(2048L), secondVector.values());
            assertFalse(scan.hasNext());
        }
    }

    @Test
    void rejectsInvalidValuesAndDetectsChecksumCorruption() throws Exception {
        Path rejected = directory.resolve("rejected.duckdb");
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(rejected, new byte[16])) {
            DuckDbPrimitivePayloadWriter writer = new DuckDbPrimitivePayloadWriter(manager);
            assertThrows(StorageFormatException.class, () -> writer.write(
                    DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN, List.of(2L)));
            assertThrows(StorageFormatException.class, () -> writer.write(
                    DuckDbTableCreateInfo.ScalarLogicalType.INTEGER, List.of((long) Integer.MAX_VALUE + 1)));
            assertThrows(StorageFormatException.class, () -> writer.write(
                    DuckDbTableCreateInfo.ScalarLogicalType.BIGINT,
                    java.util.Collections.nCopies(DuckDbPrimitivePayloadWriter.VECTOR_SIZE + 1, 0L)));
            assertEquals(0, manager.activeHeader().blockCount());
        }

        Path corrupted = directory.resolve("corrupted.duckdb");
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(corrupted, new byte[16])) {
            new DuckDbPrimitivePayloadWriter(manager).write(DuckDbTableCreateInfo.ScalarLogicalType.BOOLEAN,
                    List.of(1L));
        }
        byte[] disk = Files.readAllBytes(corrupted);
        disk[(int) (StorageFormat.BLOCK_START + StorageFormat.DEFAULT_BLOCK_HEADER_SIZE)] ^= 1;
        Files.write(corrupted, disk);
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(corrupted)) {
            assertThrows(StorageFormatException.class, () -> manager.readBlock(0));
        }
    }
}
