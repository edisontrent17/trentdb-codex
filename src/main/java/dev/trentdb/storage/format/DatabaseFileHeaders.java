package dev.trentdb.storage.format;

import java.util.Arrays;

/**
 * Validates and selects the active checkpoint header from a DuckDB V2.0 file prefix.
 *
 * <p>This deliberately stops before metadata-block, catalog, free-list, or table decoding.
 * A parsed instance establishes only that the durable three-header prefix is valid.</p>
 */
public record DatabaseFileHeaders(MainHeader mainHeader, DatabaseHeader activeHeader, int activeHeaderIndex) {
    public static DatabaseFileHeaders read(byte[] filePrefix) {
        if (filePrefix == null || filePrefix.length < StorageFormat.MINIMUM_DATABASE_FILE_SIZE) {
            throw new StorageFormatException("Truncated DuckDB database: expected at least "
                    + StorageFormat.MINIMUM_DATABASE_FILE_SIZE + " bytes for file headers");
        }
        MainHeader main = HeaderCodec.decodeMain(page(filePrefix, 0));
        DatabaseHeader first = HeaderCodec.decodeDatabase(page(filePrefix, StorageFormat.FILE_HEADER_SIZE));
        DatabaseHeader second = HeaderCodec.decodeDatabase(page(filePrefix, StorageFormat.FILE_HEADER_SIZE * 2));
        // DuckDB chooses h2 when iterations are equal.
        return first.iteration() > second.iteration()
                ? new DatabaseFileHeaders(main, first, 0)
                : new DatabaseFileHeaders(main, second, 1);
    }

    public static byte[] createEmptyV2(byte[] databaseIdentifier) {
        byte[] prefix = new byte[StorageFormat.MINIMUM_DATABASE_FILE_SIZE];
        copy(HeaderCodec.encodeMain(MainHeader.emptyV2(databaseIdentifier)), prefix, 0);
        byte[] checkpoint = HeaderCodec.encodeDatabase(DatabaseHeader.emptyV2());
        copy(checkpoint, prefix, StorageFormat.FILE_HEADER_SIZE);
        copy(checkpoint, prefix, StorageFormat.FILE_HEADER_SIZE * 2);
        return prefix;
    }

    private static byte[] page(byte[] source, int offset) {
        return Arrays.copyOfRange(source, offset, offset + StorageFormat.FILE_HEADER_SIZE);
    }

    private static void copy(byte[] source, byte[] target, int offset) {
        System.arraycopy(source, 0, target, offset, source.length);
    }
}
