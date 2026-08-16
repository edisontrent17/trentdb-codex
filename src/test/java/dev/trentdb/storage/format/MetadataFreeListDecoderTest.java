package dev.trentdb.storage.format;

import dev.trentdb.storage.MetadataFreeList;
import dev.trentdb.storage.MetadataFreeListDecoder;
import dev.trentdb.storage.SingleFileBlockManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataFreeListDecoderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void decodesTheFixedWidthFreeListRootBeforeCatalogSerialization() throws IOException {
        Path file = createFreeListFixture("free-list.duckdb", 2);
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(file)) {
            MetadataFreeList freeList = MetadataFreeListDecoder.decodeActiveFreeList(manager);
            assertEquals(List.of(2L, 3L), freeList.freeBlocks());
            assertEquals(Map.of(4L, 7L), freeList.multiUseBlockCounts());
            assertEquals(List.of(new MetadataFreeList.MetadataBlockFreeList(5, List.of(63, 1)),
                    new MetadataFreeList.MetadataBlockFreeList(0, List.of())), freeList.metadataBlocks());
        }
    }

    @Test
    void rejectsAFreeListCountOutsideTheCommittedBlockRange() throws IOException {
        Path file = createFreeListFixture("free-list-corrupt.duckdb", 7);
        try (SingleFileBlockManager manager = SingleFileBlockManager.openMetadataReadOnly(file)) {
            StorageFormatException failure = assertThrows(StorageFormatException.class,
                    () -> MetadataFreeListDecoder.decodeActiveFreeList(manager));
            assertEquals("Invalid DuckDB free block count 7; expected a value in [0, 6]", failure.getMessage());
        }
    }

    private Path createFreeListFixture(String name, long freeBlockCount) throws IOException {
        Path file = temporaryDirectory.resolve(name);
        try (SingleFileBlockManager manager = SingleFileBlockManager.create(file, new byte[16])) {
            int metadataBlockSize = MetadataBlockLayout.metadataBlockSize(manager.usableBlockSize());
            byte[] freeListBlock = new byte[manager.usableBlockSize()];
            ByteBuffer payload = ByteBuffer.wrap(freeListBlock).order(ByteOrder.LITTLE_ENDIAN);
            payload.putLong(MetaBlockPointer.INVALID_BLOCK_POINTER);
            payload.putLong(freeBlockCount);
            payload.putLong(2);
            payload.putLong(3);
            payload.putLong(1);
            payload.putLong(4);
            payload.putInt(7);
            payload.putLong(2);
            payload.putLong(5);
            payload.putLong((1L << 63) | (1L << 1));
            payload.putLong(0);
            payload.putLong(0);
            Arrays.fill(freeListBlock, payload.position(), metadataBlockSize, (byte) 0);
            manager.writeBlock(0, freeListBlock);
            for (int blockId = 1; blockId < 6; blockId++) {
                manager.writeBlock(blockId, new byte[manager.usableBlockSize()]);
            }
        }
        installFreeListRoot(file);
        return file;
    }

    private static void installFreeListRoot(Path file) throws IOException {
        byte[] prefix = java.nio.file.Files.readAllBytes(file);
        DatabaseFileHeaders headers = DatabaseFileHeaders.read(prefix);
        DatabaseHeader active = headers.activeHeader();
        DatabaseHeader next = new DatabaseHeader(active.iteration() + 1, StorageFormat.INVALID_BLOCK,
                MetaBlockPointer.of(0, 0, 0).blockPointer(), active.blockCount(), active.blockAllocationSize(),
                active.vectorSize(), active.storageCompatibility());
        int inactiveIndex = 1 - headers.activeHeaderIndex();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(HeaderCodec.encodeDatabase(next)),
                    (long) (inactiveIndex + 1) * StorageFormat.FILE_HEADER_SIZE);
        }
    }
}
