package dev.trentdb.storage;

import dev.trentdb.storage.format.MetaBlockPointer;
import dev.trentdb.storage.format.StorageFormatException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DuckDbTableCreateInfoTest {
    @TempDir Path temporaryDirectory;

    @Test
    void readsTableCreateInfoPrefixAndStopsBeforeNativeTableMetadata() {
        byte[] payload = {
                100, 0, 1, 99, 0, 1, 100, 0, 1,
                100, 0, 1, 105, 0, 0,
                111, 0, 100, 0, 3, 6, 'm','e','m','o','r','y', 4, 'm','a','i','n', 1, 't', (byte) 0xff, (byte) 0xff,
                (byte) 200, 0, 1, 't', (byte) 201, 0,
                100, 0, 1,
                100, 0, 1, 'c', 101, 0, 100, 0, 13, (byte) 0xff, (byte) 0xff,
                103, 0, 0, 104, 0, 0, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };
        try (SingleFileBlockManager manager = metadataFile("table-prefix.duckdb", payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager, MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint();
            assertEquals(DuckDbCheckpointEnvelopeReader.CatalogEntryPayloadOutcome.TABLE_CREATE_INFO_PREFIX_AVAILABLE,
                    reader.readNextEntryEnvelope().payloadOutcome());
            assertEquals(new DuckDbTableCreateInfo(List.of("memory", "main", "t"), false, false,
                    DuckDbSequenceCreateInfo.OnCreateConflict.ERROR_ON_CONFLICT, "", "", "t",
                    List.of(new DuckDbTableCreateInfo.Column("c", DuckDbTableCreateInfo.ScalarLogicalType.INTEGER,
                            DuckDbTableCreateInfo.Category.STANDARD, DuckDbTableCreateInfo.Compression.AUTO)),
                    DuckDbTableCreateInfo.Boundary.TABLE_METADATA_FIELD_101_UNSUPPORTED), reader.readTableCreateInfoPrefix());
        }
    }

    @Test
    void rejectsLogicalTypeInfoAndTableConstraintsWithoutSkipping() {
        assertFailure("type-info.duckdb", new byte[] {
                100,0,1,99,0,1,100,0,1,100,0,1,105,0,0,(byte)201,0,100,0,1,
                100,0,1,'c',101,0,100,0,13,101,0
        }, "DuckDB LogicalType ExtraTypeInfo field 101 is unsupported");
        assertFailure("constraints.duckdb", new byte[] {
                100,0,1,99,0,1,100,0,1,100,0,1,105,0,0,(byte)201,0,100,0,0,(byte)0xff,(byte)0xff,
                (byte)202,0
        }, "DuckDB TableCreateInfo constraints field 202 is unsupported when non-default");
    }

    private void assertFailure(String name, byte[] payload, String message) {
        try (SingleFileBlockManager manager = metadataFile(name, payload)) {
            DuckDbCheckpointEnvelopeReader reader = new DuckDbCheckpointEnvelopeReader(manager, MetaBlockPointer.of(0, 0, 0));
            reader.beginCheckpoint(); reader.readNextEntryEnvelope();
            StorageFormatException exception = assertThrows(StorageFormatException.class, reader::readTableCreateInfoPrefix);
            assertEquals(message, exception.getMessage());
        }
    }

    private SingleFileBlockManager metadataFile(String name, byte[] payload) {
        Path path = temporaryDirectory.resolve(name);
        try (SingleFileBlockManager writer = SingleFileBlockManager.create(path, new byte[16])) {
            byte[] block = new byte[writer.usableBlockSize()];
            for (int index = 0; index < Long.BYTES; index++) block[index] = (byte) (MetaBlockPointer.INVALID_BLOCK_POINTER >>> (index * 8));
            System.arraycopy(payload, 0, block, Long.BYTES, payload.length);
            writer.writeBlock(0, block);
        }
        return SingleFileBlockManager.openMetadataReadOnly(path);
    }
}
