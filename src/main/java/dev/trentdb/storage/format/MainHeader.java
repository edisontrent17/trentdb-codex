package dev.trentdb.storage.format;

import java.util.Arrays;

/** Main-header payload, serialized after its eight-byte checksum. */
public record MainHeader(long versionNumber, long[] flags, byte[] libraryVersion, byte[] librarySourceId,
                         byte[] encryptionMetadata, byte[] databaseIdentifier, byte[] encryptedCanary,
                         byte[] canaryIv, byte[] canaryTag) {
    public static final int FLAG_COUNT = 4;

    public MainHeader {
        flags = copy(flags, FLAG_COUNT, "flags");
        libraryVersion = copy(libraryVersion, 32, "libraryVersion");
        librarySourceId = copy(librarySourceId, 32, "librarySourceId");
        encryptionMetadata = copy(encryptionMetadata, 8, "encryptionMetadata");
        databaseIdentifier = copy(databaseIdentifier, 16, "databaseIdentifier");
        encryptedCanary = copy(encryptedCanary, 8, "encryptedCanary");
        canaryIv = copy(canaryIv, 12, "canaryIv");
        canaryTag = copy(canaryTag, 16, "canaryTag");
    }

    public static MainHeader emptyV2(byte[] databaseIdentifier) {
        return new MainHeader(StorageFormat.DEPRECATED_MAIN_HEADER_VERSION, new long[FLAG_COUNT], new byte[32],
                new byte[32], new byte[8], databaseIdentifier, new byte[8], new byte[12], new byte[16]);
    }

    public boolean isEncrypted() {
        return (flags[0] & 1L) != 0;
    }

    private static long[] copy(long[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must contain " + length + " values");
        }
        return value.clone();
    }

    private static byte[] copy(byte[] value, int length, String name) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(name + " must contain " + length + " bytes");
        }
        return Arrays.copyOf(value, length);
    }
}
