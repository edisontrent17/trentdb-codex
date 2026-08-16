package dev.trentdb.storage.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Binary codecs for DuckDB V2.0 main and checkpoint header pages. */
public final class HeaderCodec {
    private HeaderCodec() {
    }

    public static byte[] encodeMain(MainHeader header) {
        if (header.versionNumber() != StorageFormat.DEPRECATED_MAIN_HEADER_VERSION) {
            throw new StorageFormatException("DuckDB V2.0 main header must use version marker 999");
        }
        byte[] page = new byte[StorageFormat.FILE_HEADER_SIZE];
        ByteBuffer buffer = payload(page);
        buffer.put(StorageFormat.MAGIC_BYTES);
        buffer.putLong(header.versionNumber());
        for (long flag : header.flags()) {
            buffer.putLong(flag);
        }
        buffer.put(header.libraryVersion());
        buffer.put(header.librarySourceId());
        buffer.put(header.encryptionMetadata());
        buffer.put(header.databaseIdentifier());
        buffer.put(header.encryptedCanary());
        buffer.put(header.canaryIv());
        buffer.put(header.canaryTag());
        writeChecksum(page);
        return page;
    }

    public static MainHeader decodeMain(byte[] page) {
        validatePage(page, "main");
        verifyChecksum(page, "main");
        ByteBuffer buffer = payload(page);
        byte[] magic = bytes(buffer, StorageFormat.MAGIC_BYTES.length);
        if (!Arrays.equals(magic, StorageFormat.MAGIC_BYTES)) {
            throw new StorageFormatException("The file is not a valid DuckDB database file: missing DUCK magic");
        }
        long version = buffer.getLong();
        if (version != StorageFormat.DEPRECATED_MAIN_HEADER_VERSION) {
            throw new StorageFormatException("Unsupported DuckDB main-header version " + version
                    + "; V2.0 files use deprecated marker 999");
        }
        long[] flags = new long[MainHeader.FLAG_COUNT];
        for (int index = 0; index < flags.length; index++) {
            flags[index] = buffer.getLong();
        }
        return new MainHeader(version, flags, bytes(buffer, 32), bytes(buffer, 32), bytes(buffer, 8),
                bytes(buffer, 16), bytes(buffer, 8), bytes(buffer, 12), bytes(buffer, 16));
    }

    public static byte[] encodeDatabase(DatabaseHeader header) {
        validateDatabase(header);
        byte[] page = new byte[StorageFormat.FILE_HEADER_SIZE];
        ByteBuffer buffer = payload(page);
        buffer.putLong(header.iteration());
        buffer.putLong(header.metaBlock());
        buffer.putLong(header.freeList());
        buffer.putLong(header.blockCount());
        buffer.putLong(header.blockAllocationSize());
        buffer.putLong(header.vectorSize());
        buffer.putLong(header.storageCompatibility());
        writeChecksum(page);
        return page;
    }

    public static DatabaseHeader decodeDatabase(byte[] page) {
        validatePage(page, "database");
        verifyChecksum(page, "database");
        ByteBuffer buffer = payload(page);
        DatabaseHeader header = new DatabaseHeader(buffer.getLong(), buffer.getLong(), buffer.getLong(),
                buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
        validateDatabase(header);
        return header;
    }

    static void writeChecksum(byte[] page) {
        DuckDbChecksum.putLittleEndianLong(page, 0, DuckDbChecksum.checksum(page,
                StorageFormat.HEADER_CHECKSUM_SIZE, StorageFormat.FILE_HEADER_SIZE - StorageFormat.HEADER_CHECKSUM_SIZE));
    }

    static void verifyChecksum(byte[] page, String name) {
        long stored = DuckDbChecksum.littleEndianLong(page, 0);
        long computed = DuckDbChecksum.checksum(page, StorageFormat.HEADER_CHECKSUM_SIZE,
                StorageFormat.FILE_HEADER_SIZE - StorageFormat.HEADER_CHECKSUM_SIZE);
        if (stored != computed) {
            throw new StorageFormatException("Corrupt DuckDB " + name + " header: checksum mismatch");
        }
    }

    static void validatePage(byte[] page, String name) {
        if (page == null || page.length != StorageFormat.FILE_HEADER_SIZE) {
            throw new StorageFormatException("Truncated DuckDB " + name + " header");
        }
    }

    private static ByteBuffer payload(byte[] page) {
        return ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).position(StorageFormat.HEADER_CHECKSUM_SIZE);
    }

    private static byte[] bytes(ByteBuffer buffer, int length) {
        byte[] result = new byte[length];
        buffer.get(result);
        return result;
    }

    private static void validateDatabase(DatabaseHeader header) {
        StorageFormat.validateBlockAllocationSize(header.blockAllocationSize());
        if (header.vectorSize() != StorageFormat.STANDARD_VECTOR_SIZE) {
            throw new StorageFormatException("Cannot read DuckDB database with vector size " + header.vectorSize()
                    + "; this build uses " + StorageFormat.STANDARD_VECTOR_SIZE);
        }
        if (header.storageCompatibility() != StorageFormat.STORAGE_VERSION) {
            throw new StorageFormatException("Unsupported DuckDB storage compatibility version "
                    + header.storageCompatibility() + "; this build supports format 69 only");
        }
    }
}
