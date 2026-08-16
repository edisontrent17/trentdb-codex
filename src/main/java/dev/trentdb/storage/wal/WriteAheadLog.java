package dev.trentdb.storage.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Append-only internal WAL. New files begin with a checksummed schema-version header; headerless legacy files are rejected. Its byte format is deliberately not compatible
 * with DuckDB WAL files. Each frame has a magic, version, sequence, length,
 * and CRC32C; recovery ignores only a torn final frame.
 */
public final class WriteAheadLog implements AutoCloseable {
    private static final int MAGIC = 0x5457414C;
    private static final int FILE_MAGIC = 0x54574C46; // TWLF
    private static final short FILE_SCHEMA_VERSION = 2;
    private static final int FILE_HEADER_BYTES = 16;
    private static final short VERSION = 1;
    private static final int HEADER_BYTES = 32;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private final Path path;
    private final FileChannel channel;
    private long nextSequence;
    private boolean closed;

    private WriteAheadLog(Path path, FileChannel channel, long nextSequence) {
        this.path = path;
        this.channel = channel;
        this.nextSequence = nextSequence;
    }

    /** Opens a complete WAL for appending. Torn WAL tails must be recovered first. */
    public static WriteAheadLog open(Path path) {
        try {
            var frames = readFrames(path, false);
            var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (channel.size() == 0) writeFileHeader(channel);
            long next = frames.isEmpty() ? 1 : frames.get(frames.size() - 1).sequence() + 1;
            return new WriteAheadLog(path, channel, next);
        } catch (IOException exception) {
            throw new WalException("Unable to open WAL: " + path, exception);
        }
    }

    public synchronized WalRecord appendBegin(long transactionId) { return append(transactionId, WalRecordType.BEGIN, new byte[0]); }
    public synchronized WalRecord appendWrite(long transactionId, byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("WAL write payload must not be null");
        return append(transactionId, WalRecordType.WRITE, payload);
    }
    public synchronized WalRecord appendCommit(long transactionId, long commitVersion) {
        if (commitVersion < 0) throw new IllegalArgumentException("Commit version must not be negative");
        return append(transactionId, WalRecordType.COMMIT, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(commitVersion).array());
    }
    /** A rollback after commit is a compensating abort when publication failed. */
    public synchronized WalRecord appendRollback(long transactionId) { return append(transactionId, WalRecordType.ROLLBACK, new byte[0]); }

    /** Forces all appended frames before a caller publishes committed state. */
    public synchronized void force() {
        requireOpen();
        try { channel.force(true); } catch (IOException exception) { throw new WalException("Unable to force WAL", exception); }
    }

    /** Strict decoding: any torn final frame is an error. */
    public synchronized List<WalRecord> readAllRecords() { requireOpen(); return List.copyOf(readFrames(path, false)); }

    /** Deterministically filters to transactions whose final terminal record is COMMIT. */
    public synchronized List<RecoveredTransaction> recoverCommittedTransactions() { requireOpen(); return recover(path); }

    public static List<RecoveredTransaction> recover(Path path) {
        var active = new LinkedHashMap<Long, Pending>();
        var committed = new LinkedHashMap<Long, RecoveredTransaction>();
        for (var frame : readFrames(path, true)) {
            switch (frame.type()) {
                case BEGIN -> {
                    requireEmpty(frame);
                    if (active.containsKey(frame.transactionId()) || committed.containsKey(frame.transactionId()))
                        throw new WalException("Duplicate WAL begin: " + frame.transactionId());
                    active.put(frame.transactionId(), new Pending());
                }
                case WRITE -> {
                    var pending = active.get(frame.transactionId());
                    if (pending == null) throw new WalException("WAL write without begin: " + frame.transactionId());
                    pending.writes.add(frame.payload());
                }
                case COMMIT -> {
                    var pending = active.remove(frame.transactionId());
                    if (pending == null) throw new WalException("WAL commit without begin: " + frame.transactionId());
                    committed.put(frame.transactionId(), new RecoveredTransaction(frame.transactionId(), commitVersion(frame), pending.writes));
                }
                case ROLLBACK -> {
                    requireEmpty(frame);
                    active.remove(frame.transactionId());
                    committed.remove(frame.transactionId());
                }
            }
        }
        return List.copyOf(committed.values());
    }

    @Override public synchronized void close() {
        if (closed) return;
        try { channel.close(); closed = true; } catch (IOException exception) { throw new WalException("Unable to close WAL", exception); }
    }

    private WalRecord append(long transactionId, WalRecordType type, byte[] payload) {
        requireOpen();
        if (transactionId <= 0 || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Invalid WAL frame");
        byte[] copy = Arrays.copyOf(payload, payload.length);
        long sequence = nextSequence;
        var frame = ByteBuffer.allocate(HEADER_BYTES + copy.length).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).putShort(VERSION).put((byte) type.id()).put((byte) 0);
        frame.putLong(transactionId).putLong(sequence).putInt(copy.length);
        frame.putInt(checksum(transactionId, sequence, type, copy)).put(copy).flip();
        try {
            channel.position(channel.size());
            while (frame.hasRemaining()) channel.write(frame);
            nextSequence++;
            return new WalRecord(transactionId, sequence, type, copy);
        } catch (IOException exception) {
            throw new WalException("Unable to append WAL frame", exception);
        }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("WAL is closed"); }

    private static List<WalRecord> readFrames(Path path, boolean tolerateTail) {
        if (!Files.exists(path)) return List.of();
        try (var input = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = input.size(), offset = fileHeaderOffset(input, size, tolerateTail), previousSequence = 0;
            var result = new ArrayList<WalRecord>();
            while (offset < size) {
                long remaining = size - offset;
                if (remaining < HEADER_BYTES) { if (tolerateTail) break; throw new WalException("Truncated WAL header at " + offset); }
                var header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
                readFully(input, header, offset);
                header.flip();
                int magic = header.getInt();
                short version = header.getShort();
                int typeId = Byte.toUnsignedInt(header.get());
                header.get();
                long transactionId = header.getLong(), sequence = header.getLong();
                int length = header.getInt(), expectedChecksum = header.getInt();
                if (magic != MAGIC || version != VERSION || transactionId <= 0 || sequence <= previousSequence || length < 0 || length > MAX_PAYLOAD)
                    throw new WalException("Invalid WAL frame header at " + offset);
                if (remaining - HEADER_BYTES < length) { if (tolerateTail) break; throw new WalException("Truncated WAL payload at " + offset); }
                byte[] payload = new byte[length];
                if (length > 0) readFully(input, ByteBuffer.wrap(payload), offset + HEADER_BYTES);
                var type = WalRecordType.fromId(typeId);
                if (expectedChecksum != checksum(transactionId, sequence, type, payload)) throw new WalException("WAL checksum mismatch at " + offset);
                result.add(new WalRecord(transactionId, sequence, type, payload));
                previousSequence = sequence;
                offset += HEADER_BYTES + length;
            }
            return result;
        } catch (IOException exception) {
            throw new WalException("Unable to read WAL: " + path, exception);
        }
    }

    private static long fileHeaderOffset(FileChannel input, long size, boolean tolerateTail) throws IOException {
        if (size == 0) return 0;
        if (size < FILE_HEADER_BYTES) { if (tolerateTail) return size; throw new WalException("Truncated WAL file header"); }
        var header = ByteBuffer.allocate(FILE_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN); readFully(input, header, 0); header.flip();
        int magic = header.getInt(); short version = header.getShort(); short reserved = header.getShort(); int flags = header.getInt(); int expected = header.getInt();
        if (magic == MAGIC) throw new WalException("Legacy headerless WAL is unsupported; migrate or discard before opening");
        if (magic != FILE_MAGIC) throw new WalException("Invalid WAL file magic");
        if (version != FILE_SCHEMA_VERSION) throw new WalException("Unsupported WAL file schema version: " + version);
        if (reserved != 0 || flags != 0 || expected != fileHeaderChecksum(version, flags)) throw new WalException("Invalid WAL file header");
        return FILE_HEADER_BYTES;
    }

    private static void writeFileHeader(FileChannel channel) throws IOException {
        var header = ByteBuffer.allocate(FILE_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(FILE_MAGIC).putShort(FILE_SCHEMA_VERSION).putShort((short) 0).putInt(0).putInt(fileHeaderChecksum(FILE_SCHEMA_VERSION, 0)).flip();
        while (header.hasRemaining()) channel.write(header); channel.force(true);
    }

    private static int fileHeaderChecksum(short version, int flags) {
        var header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN); header.putInt(FILE_MAGIC).putShort(version).putShort((short) 0).putInt(flags);
        var crc = new CRC32C(); crc.update(header.array(), 0, header.position()); return (int) crc.getValue();
    }


    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int count = channel.read(target, offset);
            if (count < 0) throw new IOException("Unexpected EOF");
            offset += count;
        }
    }

    private static int checksum(long transactionId, long sequence, WalRecordType type, byte[] payload) {
        var header = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC).putShort(VERSION).put((byte) type.id()).put((byte) 0);
        header.putLong(transactionId).putLong(sequence).putInt(payload.length);
        var crc = new CRC32C();
        crc.update(header.array(), 0, header.position());
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }

    private static void requireEmpty(WalRecord frame) { if (frame.payload().length != 0) throw new WalException("Unexpected WAL payload for " + frame.type()); }
    private static long commitVersion(WalRecord frame) {
        byte[] payload = frame.payload();
        if (payload.length != 8) throw new WalException("Invalid WAL commit payload");
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong();
    }
    private static final class Pending { private final List<byte[]> writes = new ArrayList<>(); }
}
