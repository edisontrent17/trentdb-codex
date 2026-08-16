package dev.trentdb.storage;

import dev.trentdb.storage.format.DatabaseFileHeaders;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boundary test for a file written by the pinned native DuckDB test oracle.
 *
 * <p>It is deliberately opt-in: {@code scripts/verify-duckdb-storage-interop.sh} creates the
 * fixture with a native DuckDB CLI and passes its path using {@code duckdb.interop.fixture}.
 * Native DuckDB is never a Java runtime dependency. The test validates only the three-header
 * prefix. A populated DuckDB file still has unsupported catalog metadata and free-list blocks.</p>
 */
class DuckDbProducedFormat69InteropTest {
    private static final String FIXTURE_PROPERTY = "duckdb.interop.fixture";

    @Test
    void readsAFormat69PrefixProducedByNativeDuckDbAndRejectsUnsupportedMetadata() throws IOException {
        String configuredFixture = System.getProperty(FIXTURE_PROPERTY);
        Assumptions.assumeTrue(configuredFixture != null && !configuredFixture.isBlank(),
                "Set " + FIXTURE_PROPERTY + " with a fixture made by the native DuckDB test oracle");

        Path fixture = Path.of(configuredFixture);
        byte[] file = Files.readAllBytes(fixture);
        DatabaseFileHeaders headers = DatabaseFileHeaders.read(file);

        assertEquals(StorageFormat.DEPRECATED_MAIN_HEADER_VERSION, headers.mainHeader().versionNumber());
        assertEquals(StorageFormat.STORAGE_VERSION, headers.activeHeader().storageCompatibility());
        assertEquals(StorageFormat.STANDARD_VECTOR_SIZE, headers.activeHeader().vectorSize());
        assertEquals(StorageFormat.DEFAULT_BLOCK_ALLOCATION_SIZE, headers.activeHeader().blockAllocationSize());
        assertNotEquals(StorageFormat.INVALID_BLOCK, headers.activeHeader().metaBlock(),
                "The native checkpoint fixture must contain a catalog metadata root");

        try (SingleFileBlockManager metadataManager = SingleFileBlockManager.openMetadataReadOnly(fixture)) {
            MetadataChainReader metadata = new MetadataChainReader(metadataManager,
                    new dev.trentdb.storage.format.MetaBlockPointer(headers.activeHeader().metaBlock(), 0));
            metadata.readFully(1);
        }

        StorageFormatException failure = assertThrows(StorageFormatException.class,
                () -> SingleFileBlockManager.open(fixture));
        assertEquals("DuckDB metadata and free-list blocks are not supported by this reader", failure.getMessage());
    }
}
