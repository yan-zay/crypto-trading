package com.tj.crypto.marketdata.archive;

import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Hadoop-runtime-free codec factory for local immutable Parquet artifacts. */
final class UncompressedParquetCodecFactory implements CompressionCodecFactory {
    static final UncompressedParquetCodecFactory INSTANCE = new UncompressedParquetCodecFactory();

    private static final BytesInputCompressor COMPRESSOR = new BytesInputCompressor() {
        @Override
        public BytesInput compress(BytesInput bytes) throws IOException {
            return BytesInput.from(bytes.toByteArray());
        }

        @Override
        public CompressionCodecName getCodecName() {
            return CompressionCodecName.UNCOMPRESSED;
        }

        @Override public void release() {}
    };

    private static final BytesInputDecompressor DECOMPRESSOR = new BytesInputDecompressor() {
        @Override
        public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
            byte[] value = bytes.toByteArray();
            if (value.length != uncompressedSize) {
                throw new IOException("Uncompressed Parquet page length mismatch");
            }
            return BytesInput.from(value);
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize,
                               ByteBuffer output, int uncompressedSize) throws IOException {
            if (compressedSize != uncompressedSize || input.remaining() < compressedSize
                    || output.remaining() < uncompressedSize) {
                throw new IOException("Uncompressed Parquet buffer length mismatch");
            }
            ByteBuffer slice = input.slice();
            slice.limit(compressedSize);
            output.put(slice);
            input.position(input.position() + compressedSize);
        }

        @Override public void release() {}
    };

    private UncompressedParquetCodecFactory() {}

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName codecName) {
        requireUncompressed(codecName);
        return COMPRESSOR;
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
        requireUncompressed(codecName);
        return DECOMPRESSOR;
    }

    @Override public void release() {}

    private void requireUncompressed(CompressionCodecName codecName) {
        if (codecName != CompressionCodecName.UNCOMPRESSED) {
            throw new IllegalArgumentException("Only UNCOMPRESSED Parquet is supported");
        }
    }
}
