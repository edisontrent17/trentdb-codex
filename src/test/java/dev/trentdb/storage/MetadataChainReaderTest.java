package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.MetadataBlockLayout;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataChainReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsAcrossDuckDbMetadataSubBlocksAndVerifiesTheirExactAddressing() {
        Path file = temporaryDirectory.resolve("metadata-chain.duckdb");
        int metadataBlockSize;
        try (SingleFileBlockManager writer = SingleFileBlockManager.create(file, new byte[16])) {
            metadataBlockSize = MetadataBlockLayout.metadataBlockSize(writer.usableBlockSize());
            assertEquals(4_088, metadataBlockSize);
            byte[] first = metadataBlock(MetaBlockPointer.of(1, 0, 0).blockPointer(), (byte) 'a', metadataBlockSize,
                    writer.usableBlockSize());
            byte[] second = metadataBlock(MetaBlockPointer.INVALID_BLOCK_POINTER, (byte) 'b', metadataBlockSize,
                    writer.usableBlockSize());
            writer.writeBlock(0, first);
            writer.writeBlock(1, second);
        }

        try (SingleFileBlockManager readerManager = SingleFileBlockManager.openMetadataReadOnly(file)) {
            MetadataChainReader reader = new MetadataChainReader(readerManager, MetaBlockPointer.of(0, 0, 0));
            byte[] data = reader.readFully(metadataBlockSize - Long.BYTES + 5);
            assertArrayEquals(repeated((byte) 'a', metadataBlockSize - Long.BYTES),
                    Arrays.copyOf(data, metadataBlockSize - Long.BYTES));
            assertArrayEquals("bbbbb".getBytes(StandardCharsets.US_ASCII),
                    Arrays.copyOfRange(data, metadataBlockSize - Long.BYTES, data.length));
            assertEquals(MetaBlockPointer.of(1, 0, 13), reader.currentPointer());
            assertThrows(StorageException.class, () -> readerManager.writeBlock(0, new byte[0]));
        }
    }

    @Test
    void malformedChainsAndPackedPointersAreRejected() {
        Path file = temporaryDirectory.resolve("metadata-cycle.duckdb");
        int metadataBlockSize;
        try (SingleFileBlockManager writer = SingleFileBlockManager.create(file, new byte[16])) {
            metadataBlockSize = MetadataBlockLayout.metadataBlockSize(writer.usableBlockSize());
            writer.writeBlock(0, metadataBlock(MetaBlockPointer.of(0, 0, 0).blockPointer(), (byte) 1,
                    metadataBlockSize, writer.usableBlockSize()));
        }

        try (SingleFileBlockManager readerManager = SingleFileBlockManager.openMetadataReadOnly(file)) {
            MetadataChainReader reader = new MetadataChainReader(readerManager, MetaBlockPointer.of(0, 0, 0));
            StorageFormatException cycle = assertThrows(StorageFormatException.class,
                    () -> reader.readFully(metadataBlockSize - Long.BYTES + 1));
            assertEquals("DuckDB metadata chain contains a cycle at MetaBlockPointer[blockPointer=0, offset=0]",
                    cycle.getMessage());
        }

        assertEquals(7, MetaBlockPointer.of(7, 63, 17).blockId());
        assertEquals(63, MetaBlockPointer.of(7, 63, 17).blockIndex());
        assertEquals(17, MetaBlockPointer.of(7, 63, 17).offset());
        assertThrows(StorageFormatException.class, () -> MetaBlockPointer.of(7, 64, 0));
        assertThrows(StorageFormatException.class, () -> MetaBlockPointer.of(1L << 56, 0, 0));
        assertThrows(StorageFormatException.class,
                () -> MetadataBlockLayout.blockOffset(MetaBlockPointer.of(0, 0, 4_089),
                        StorageFormat.DEFAULT_BLOCK_SIZE));
    }

    private static byte[] metadataBlock(long nextPointer, byte fill, int metadataBlockSize, int usableBlockSize) {
        byte[] result = new byte[usableBlockSize];
        putLittleEndianLong(result, 0, nextPointer);
        Arrays.fill(result, Long.BYTES, metadataBlockSize, fill);
        return result;
    }

    private static byte[] repeated(byte value, int count) {
        byte[] result = new byte[count];
        Arrays.fill(result, value);
        return result;
    }

    private static void putLittleEndianLong(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (index * 8));
        }
    }
}
