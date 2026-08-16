package dev.trentdb.storage.format;

import dev.trentdb.common.VectorSize;
import dev.trentdb.storage.InMemoryTableStorage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseFileHeadersTest {
    @Test
    void emptyV2PrefixRoundTripsAndUsesSecondHeaderOnTie() {
        byte[] identifier = new byte[16];
        identifier[0] = 42;
        DatabaseFileHeaders headers = DatabaseFileHeaders.read(DatabaseFileHeaders.createEmptyV2(identifier));

        assertEquals(1, headers.activeHeaderIndex());
        assertEquals(69, headers.activeHeader().storageCompatibility());
        assertEquals(2048, headers.activeHeader().vectorSize());
        assertArrayEquals(identifier, headers.mainHeader().databaseIdentifier());
        assertEquals(2048, VectorSize.STANDARD_VECTOR_SIZE);
        assertEquals(2048, InMemoryTableStorage.STANDARD_VECTOR_SIZE);
    }

    @Test
    void highestIterationHeaderBecomesActive() {
        byte[] prefix = DatabaseFileHeaders.createEmptyV2(new byte[16]);
        DatabaseHeader newer = new DatabaseHeader(7, 3, 4, 9, StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE,
                StorageFormat.STANDARD_VECTOR_SIZE, StorageFormat.STORAGE_VERSION);
        System.arraycopy(HeaderCodec.encodeDatabase(newer), 0, prefix, StorageFormat.FILE_HEADER_SIZE,
                StorageFormat.FILE_HEADER_SIZE);

        DatabaseFileHeaders headers = DatabaseFileHeaders.read(prefix);
        assertEquals(0, headers.activeHeaderIndex());
        assertEquals(newer, headers.activeHeader());
    }

    @Test
    void checksumCorruptionAndTruncationAreRejected() {
        byte[] prefix = DatabaseFileHeaders.createEmptyV2(new byte[16]);
        prefix[StorageFormat.FILE_HEADER_SIZE + 100] ^= 1;
        assertThrows(StorageFormatException.class, () -> DatabaseFileHeaders.read(prefix));
        assertThrows(StorageFormatException.class,
                () -> DatabaseFileHeaders.read(Arrays.copyOf(prefix, StorageFormat.MINIMUM_DATABASE_FILE_SIZE - 1)));
    }

    @Test
    void formatAndVectorMismatchesAreRejectedBeforeWriting() {
        DatabaseHeader wrongVector = new DatabaseHeader(0, -1, -1, 0, StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE,
                1024, StorageFormat.STORAGE_VERSION);
        DatabaseHeader wrongFormat = new DatabaseHeader(0, -1, -1, 0, StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE,
                StorageFormat.STANDARD_VECTOR_SIZE, 68);

        assertThrows(StorageFormatException.class, () -> HeaderCodec.encodeDatabase(wrongVector));
        assertThrows(StorageFormatException.class, () -> HeaderCodec.encodeDatabase(wrongFormat));
    }

    @Test
    void checksumUsesDuckDbLittleEndianWords() {
        byte[] bytes = new byte[8];
        bytes[0] = 1;
        assertEquals(5381L ^ 0xbf58476d1ce4e5b9L, DuckDbChecksum.checksum(bytes, 0, bytes.length));
    }
}
