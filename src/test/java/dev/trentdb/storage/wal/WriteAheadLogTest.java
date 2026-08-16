package dev.trentdb.storage.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WriteAheadLogTest {
    @TempDir Path temporaryDirectory;

    @Test
    void writesVersionedFileHeaderAndReopens() throws Exception {
        var path = temporaryDirectory.resolve("header.wal"); try (var wal = WriteAheadLog.open(path)) { assertEquals(16, Files.size(path)); wal.appendBegin(1); wal.appendCommit(1, 1); wal.force(); }
        try (var reopened = WriteAheadLog.open(path)) { assertEquals(2, reopened.readAllRecords().size()); }
    }

    @Test
    void rejectsLegacyHeaderlessUnknownVersionAndCorruptHeader() throws Exception {
        var legacy = temporaryDirectory.resolve("legacy.wal"); Files.write(legacy, java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(0x5457414C).array());
        assertThrows(WalException.class, () -> WriteAheadLog.recover(legacy));
        var unknown = temporaryDirectory.resolve("unknown.wal"); Files.write(unknown, java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(0x54574C46).putShort((short) 99).array());
        assertThrows(WalException.class, () -> WriteAheadLog.open(unknown));
        var corrupt = temporaryDirectory.resolve("header-corrupt.wal"); try (var wal = WriteAheadLog.open(corrupt)) { wal.force(); } var bytes = Files.readAllBytes(corrupt); bytes[15] ^= 1; Files.write(corrupt, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(WalException.class, () -> WriteAheadLog.recover(corrupt));
    }

    @Test
    void recoveryFiltersIncompleteAndRolledBackTransactions() {
        var path = temporaryDirectory.resolve("trent.wal"); try (var wal = WriteAheadLog.open(path)) { wal.appendBegin(1); wal.appendWrite(1, new byte[] {1, 2}); wal.appendCommit(1, 7); wal.appendBegin(2); wal.appendWrite(2, new byte[] {3}); wal.appendBegin(3); wal.appendWrite(3, new byte[] {4}); wal.appendRollback(3); wal.force(); var recovered = wal.recoverCommittedTransactions(); assertEquals(1, recovered.size()); assertEquals(1, recovered.getFirst().transactionId()); assertEquals(7, recovered.getFirst().commitVersion()); assertArrayEquals(new byte[] {1, 2}, recovered.getFirst().writes().getFirst()); }
    }

    @Test
    void recoveryIgnoresTornTailButStrictReadRejectsIt() throws Exception {
        var path = temporaryDirectory.resolve("torn.wal"); try (var wal = WriteAheadLog.open(path)) { wal.appendBegin(1); wal.appendCommit(1, 1); wal.force(); } Files.write(path, new byte[] {9, 9, 9}, StandardOpenOption.APPEND); assertEquals(1, WriteAheadLog.recover(path).size()); assertThrows(WalException.class, () -> WriteAheadLog.open(path));
    }

    @Test
    void checksumMismatchIsRejectedDuringRecovery() throws Exception {
        var path = temporaryDirectory.resolve("corrupt.wal"); try (var wal = WriteAheadLog.open(path)) { wal.appendBegin(1); wal.appendWrite(1, new byte[] {5}); wal.force(); } var bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1; Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING); assertThrows(WalException.class, () -> WriteAheadLog.recover(path));
    }
}
