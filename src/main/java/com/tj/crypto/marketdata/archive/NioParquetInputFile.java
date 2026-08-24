package com.tj.crypto.marketdata.archive;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * FileChannel-backed Parquet input that correctly supports direct and non-zero-position buffers.
 * This intentionally avoids parquet 1.17.1 LocalInputFile's known short/direct-buffer defect.
 */
public final class NioParquetInputFile implements InputFile {
    private final Path path;

    public NioParquetInputFile(Path path) {
        if (path == null) throw new IllegalArgumentException("path is required");
        this.path = path.toAbsolutePath().normalize();
        requireRegularNonSymlink(this.path);
    }

    @Override
    public long getLength() throws IOException {
        requireRegularNonSymlink(path);
        return Files.size(path);
    }

    @Override
    public SeekableInputStream newStream() throws IOException {
        requireRegularNonSymlink(path);
        return new ChannelSeekableInputStream(FileChannel.open(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    private static void requireRegularNonSymlink(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArchiveValidationException("INVALID_PARQUET_OBJECT",
                    "Parquet object must be a regular non-symlink file: " + path);
        }
    }

    static final class ChannelSeekableInputStream extends SeekableInputStream {
        private final FileChannel channel;
        private final ByteBuffer oneByte = ByteBuffer.allocate(1);

        ChannelSeekableInputStream(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public long getPos() throws IOException {
            return channel.position();
        }

        @Override
        public void seek(long newPos) throws IOException {
            if (newPos < 0 || newPos > channel.size()) {
                throw new EOFException("seek position is outside the file: " + newPos);
            }
            channel.position(newPos);
        }

        @Override
        public int read() throws IOException {
            oneByte.clear();
            int read = channel.read(oneByte);
            if (read < 0) return -1;
            oneByte.flip();
            return Byte.toUnsignedInt(oneByte.get());
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException("invalid offset/length");
            }
            if (length == 0) return 0;
            return channel.read(ByteBuffer.wrap(bytes, offset, length));
        }

        @Override
        public int read(ByteBuffer buffer) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            if (!buffer.hasRemaining()) return 0;
            return channel.read(buffer);
        }

        @Override
        public void readFully(byte[] bytes) throws IOException {
            readFully(bytes, 0, bytes.length);
        }

        @Override
        public void readFully(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException("invalid offset/length");
            }
            readFully(ByteBuffer.wrap(bytes, offset, length));
        }

        @Override
        public void readFully(ByteBuffer buffer) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) throw new EOFException("reached EOF with " + buffer.remaining() + " bytes remaining");
            }
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, channel.size() - channel.position()));
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
