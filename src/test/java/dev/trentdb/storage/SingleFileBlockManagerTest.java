package dev.trentdb.storage;

import dev.trentdb.storage.format.DatabaseFileHeaders;
import dev.trentdb.storage.format.DatabaseHeader;
import dev.trentdb.storage.format.HeaderCodec;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleFileBlockManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createAppendOverwriteAndReopenUseDuckDbBlockAddresses() throws IOException {
        Path file = temporaryDirectory.resolve("blocks.duckdb");
        byte[] first = {1, 2, 3};
        byte[] replacement = {9, 8};
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(file, new byte[16])) {
            assertEquals(StorageFormat.BLOCK_START, manager.blockOffset(0));
            assertEquals(StorageFormat.DEFAULT_BLOCK_SIZE, manager.usableBlockSize());
            assertThrows(StorageException.class, () -> manager.readBlock(0));
            assertThrows(StorageException.class, () -> manager.writeBlock(1, first));

            manager.writeBlock(0, first);
            assertArrayEquals(first, Arrays.copyOf(manager.readBlock(0), first.length));
            assertEquals(1, manager.activeHeader().blockCount());
            assertEquals(1, manager.activeHeader().iteration());
            assertEquals(StorageFormat.BLOCK_START + StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE, Files.size(file));

            manager.writeBlock(0, replacement);
            assertArrayEquals(replacement, Arrays.copyOf(manager.readBlock(0), replacement.length));
            assertEquals(2, manager.activeHeader().iteration());
            manager.sync();
        }

        DatabaseFileHeaders headers = DatabaseFileHeaders.read(Files.readAllBytes(file));
        assertEquals(1, headers.activeHeaderIndex());
        assertEquals(1, headers.activeHeader().blockCount());
        assertEquals(2, headers.activeHeader().iteration());
        try (SingleFileBlockManager manager = SingleFileBlockManager.open(file)) {
            assertArrayEquals(replacement, Arrays.copyOf(manager.readBlock(0), replacement.length));
        }
    }

    @Test
    void boundsCorruptionAndUnsupportedMetadataAreRejected() throws IOException {
        Path file = temporaryDirectory.resolve("corrupt.duckdb");
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(file, new byte[16])) {
            assertThrows(StorageException.class,
                    () -> manager.writeBlock(0, new byte[manager.usableBlockSize() + 1]));
            manager.writeBlock(0, new byte[] {1});
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {99}), StorageFormat.BLOCK_START + StorageFormat.DEFAULT_BLOCK_HEADER_SIZE);
        }
        try (SingleFileBlockManager manager = SingleFileBlockManager.open(file)) {
            assertThrows(StorageFormatException.class, () -> manager.readBlock(0));
        }

        Path metadataFile = temporaryDirectory.resolve("metadata.duckdb");
        try (SingleFileBlockManager ignored = SingleFileBlockManager.create(metadataFile, new byte[16])) {
            // Header two is active on initial equal iterations; replace it with a valid but unsupported root.
        }
        DatabaseHeader unsupported = new DatabaseHeader(1, 0, StorageFormat.INVALID_BLOCK, 0,
                StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE, StorageFormat.STANDARD_VECTOR_SIZE,
                StorageFormat.STORAGE_VERSION);
        try (FileChannel channel = FileChannel.open(metadataFile, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(HeaderCodec.encodeDatabase(unsupported)), StorageFormat.FILE_HEADER_SIZE * 2L);
        }
        assertThrows(StorageFormatException.class, () -> SingleFileBlockManager.open(metadataFile));
    }
}
