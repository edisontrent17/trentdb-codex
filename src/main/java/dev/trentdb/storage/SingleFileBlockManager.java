package dev.trentdb.storage;

import dev.trentdb.storage.format.DatabaseFileHeaders;
import dev.trentdb.storage.format.DatabaseHeader;
import dev.trentdb.storage.format.DuckDbChecksum;
import dev.trentdb.storage.format.HeaderCodec;
import dev.trentdb.storage.format.StorageFormat;
import dev.trentdb.storage.format.StorageFormatException;
import dev.trentdb.storage.format.MetaBlockPointer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Narrow DuckDB-shaped manager for the V2.0 single-file header and fixed-size block envelope.
 *
 * <p>It supports only files whose active header has no metadata or free-list root. This is
 * intentional: catalog and metadata blocks need their own reader before a populated DuckDB file
 * can be opened safely.</p>
 */
public final class SingleFileBlockManager implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final boolean writable;
    private DatabaseFileHeaders headers;
    private boolean closed;

    private SingleFileBlockManager(Path path, FileChannel channel, DatabaseFileHeaders headers, boolean writable) {
        this.path = path;
        this.channel = channel;
        this.headers = headers;
        this.writable = writable;
    }

    public static SingleFileBlockManager create(Path path, byte[] databaseIdentifier) {
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            try {
                writeFully(channel, ByteBuffer.wrap(DatabaseFileHeaders.createEmptyV2(databaseIdentifier)), 0);
                channel.force(true);
                return new SingleFileBlockManager(path, channel, DatabaseFileHeaders.read(readPrefix(channel)), true);
            } catch (RuntimeException | IOException failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to create database file " + path, exception);
        }
    }

    public static SingleFileBlockManager open(Path path) {
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                DatabaseFileHeaders headers = DatabaseFileHeaders.read(readPrefix(channel));
                rejectUnsupportedFileFeatures(headers);
                long expectedSize = Math.addExact(StorageFormat.BLOCK_START,
                        Math.multiplyExact(headers.activeHeader().blockCount(), headers.activeHeader().blockAllocationSize()));
                if (channel.size() != expectedSize) {
                    throw new StorageFormatException("Unsupported DuckDB file layout: size does not match committed block count");
                }
                return new SingleFileBlockManager(path, channel, headers, true);
            } catch (RuntimeException | IOException failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to open database file " + path, exception);
        }
    }

    /** Opens an unencrypted DuckDB file for checksum-verified raw metadata-chain reads. */
    public static SingleFileBlockManager openMetadataReadOnly(Path path) {
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            try {
                DatabaseFileHeaders headers = DatabaseFileHeaders.read(readPrefix(channel));
                if (headers.mainHeader().isEncrypted()) {
                    throw new StorageFormatException("Encrypted DuckDB files are not supported by this reader");
                }
                long committedSize = Math.addExact(StorageFormat.BLOCK_START,
                        Math.multiplyExact(headers.activeHeader().blockCount(), headers.activeHeader().blockAllocationSize()));
                if (channel.size() < committedSize) {
                    throw new StorageFormatException("Truncated DuckDB file: committed block range exceeds file size");
                }
                return new SingleFileBlockManager(path, channel, headers, false);
            } catch (RuntimeException | IOException failure) {
                channel.close();
                throw failure;
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to open DuckDB metadata file " + path, exception);
        }
    }

    public synchronized long blockOffset(long blockId) {
        requireOpen();
        if (blockId < 0) {
            throw new StorageException("Block id must not be negative: " + blockId);
        }
        try {
            return Math.addExact(StorageFormat.BLOCK_START, Math.multiplyExact(blockId, blockAllocationSize()));
        } catch (ArithmeticException exception) {
            throw new StorageException("Block offset overflows for block id " + blockId, exception);
        }
    }

    public synchronized void writeBlock(long blockId, byte[] data) {
        requireOpen();
        if (!writable) {
            throw new StorageException("Metadata-only DuckDB block manager is read-only");
        }
        if (data == null || data.length > usableBlockSize()) {
            throw new StorageException("Block data must contain at most " + usableBlockSize() + " bytes");
        }
        long blockCount = headers.activeHeader().blockCount();
        if (blockId < 0 || blockId > blockCount) {
            throw new StorageException("Block writes must overwrite a committed block or append exactly one block");
        }
        byte[] block = new byte[(int) blockAllocationSize()];
        System.arraycopy(data, 0, block, StorageFormat.DEFAULT_BLOCK_HEADER_SIZE, data.length);
        DuckDbChecksum.putLittleEndianLong(block, 0, DuckDbChecksum.checksum(block,
                StorageFormat.DEFAULT_BLOCK_HEADER_SIZE, usableBlockSize()));
        try {
            writeFully(channel, ByteBuffer.wrap(block), blockOffset(blockId));
            // A block must reach durable storage before a header can make it visible.
            channel.force(true);
            commitHeader(blockId == blockCount ? blockCount + 1 : blockCount);
        } catch (IOException exception) {
            throw new StorageException("Unable to write block " + blockId + " to " + path, exception);
        }
    }

    public synchronized byte[] readBlock(long blockId) {
        requireOpen();
        if (blockId < 0 || blockId >= headers.activeHeader().blockCount()) {
            throw new StorageException("Block id is outside the committed range: " + blockId);
        }
        byte[] block = new byte[(int) blockAllocationSize()];
        try {
            readFully(channel, ByteBuffer.wrap(block), blockOffset(blockId));
        } catch (IOException exception) {
            throw new StorageException("Unable to read block " + blockId + " from " + path, exception);
        }
        long stored = DuckDbChecksum.littleEndianLong(block, 0);
        long computed = DuckDbChecksum.checksum(block, StorageFormat.DEFAULT_BLOCK_HEADER_SIZE, usableBlockSize());
        if (stored != computed) {
            throw new StorageFormatException("Corrupt DuckDB block " + blockId + ": checksum mismatch");
        }
        return Arrays.copyOfRange(block, StorageFormat.DEFAULT_BLOCK_HEADER_SIZE, block.length);
    }

    /** Forces prior writes to stable storage. This is not a WAL commit or recovery protocol. */
    public synchronized void sync() {
        requireOpen();
        try {
            channel.force(true);
        } catch (IOException exception) {
            throw new StorageException("Unable to sync database file " + path, exception);
        }
    }

    public synchronized long blockAllocationSize() {
        return headers.activeHeader().blockAllocationSize();
    }

    public synchronized int usableBlockSize() {
        return Math.toIntExact(blockAllocationSize() - StorageFormat.DEFAULT_BLOCK_HEADER_SIZE);
    }

    public synchronized DatabaseHeader activeHeader() {
        return headers.activeHeader();
    }

    /** Durably makes an already-written metadata chain the active checkpoint root. */
    public synchronized void publishCheckpoint(MetaBlockPointer root) {
        requireOpen();
        if (!writable) throw new StorageException("Metadata-only DuckDB block manager is read-only");
        if (root == null || !root.isValid() || root.blockId() >= headers.activeHeader().blockCount()) {
            throw new StorageFormatException("DuckDB checkpoint root is outside committed blocks");
        }
        DatabaseHeader active = headers.activeHeader();
        try {
            commitHeader(new DatabaseHeader(active.iteration() + 1, root.blockPointer(), StorageFormat.INVALID_BLOCK,
                    active.blockCount(), active.blockAllocationSize(), active.vectorSize(), active.storageCompatibility()));
        } catch (IOException exception) {
            throw new StorageException("Unable to publish DuckDB checkpoint header", exception);
        }
    }

    private void commitHeader(long blockCount) throws IOException {
        DatabaseHeader active = headers.activeHeader();
        commitHeader(new DatabaseHeader(active.iteration() + 1, active.metaBlock(), active.freeList(), blockCount,
                active.blockAllocationSize(), active.vectorSize(), active.storageCompatibility()));
    }

    private void commitHeader(DatabaseHeader next) throws IOException {
        int inactiveIndex = 1 - headers.activeHeaderIndex();
        long location = (long) (inactiveIndex + 1) * StorageFormat.FILE_HEADER_SIZE;
        writeFully(channel, ByteBuffer.wrap(HeaderCodec.encodeDatabase(next)), location);
        channel.force(true);
        headers = new DatabaseFileHeaders(headers.mainHeader(), next, inactiveIndex);
    }

    private static void rejectUnsupportedFileFeatures(DatabaseFileHeaders headers) {
        if (headers.mainHeader().isEncrypted()) {
            throw new StorageFormatException("Encrypted DuckDB files are not supported by this reader");
        }
        DatabaseHeader header = headers.activeHeader();
        if (header.metaBlock() != StorageFormat.INVALID_BLOCK || header.freeList() != StorageFormat.INVALID_BLOCK) {
            throw new StorageFormatException("DuckDB metadata and free-list blocks are not supported by this reader");
        }
    }

    private static byte[] readPrefix(FileChannel channel) throws IOException {
        if (channel.size() < StorageFormat.MINIMUM_DATABASE_FILE_SIZE) {
            throw new StorageFormatException("Truncated DuckDB database file");
        }
        byte[] prefix = new byte[StorageFormat.MINIMUM_DATABASE_FILE_SIZE];
        readFully(channel, ByteBuffer.wrap(prefix), 0);
        return prefix;
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset + target.position());
            if (read == 0) {
                throw new IOException("Unable to make progress reading database file");
            }
            if (read < 0) {
                throw new StorageFormatException("Truncated DuckDB database file");
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source, long offset) throws IOException {
        while (source.hasRemaining()) {
            int written = channel.write(source, offset + source.position());
            if (written == 0) {
                throw new IOException("Unable to make progress writing database file");
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new StorageException("Block manager is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            try {
                channel.close();
            } catch (IOException exception) {
                throw new StorageException("Unable to close database file " + path, exception);
            } finally {
                closed = true;
            }
        }
    }
}
