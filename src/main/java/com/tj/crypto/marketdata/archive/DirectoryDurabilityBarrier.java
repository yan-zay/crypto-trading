package com.tj.crypto.marketdata.archive;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Persists a directory entry after a new file or atomic rename is published.
 *
 * <p>The default implementation is intentionally strict. Some filesystems/JDKs (notably common
 * Windows configurations) do not expose a verifiable directory fsync. In that case the operation
 * is rejected as {@code DIRECTORY_DURABILITY_NOT_VERIFIED}; callers must not return a receipt that
 * claims crash durability. Tests may inject a recording implementation to verify ordering without
 * pretending that the host performed a real directory fsync.</p>
 */
@FunctionalInterface
public interface DirectoryDurabilityBarrier {
    void force(Path directory);

    static DirectoryDurabilityBarrier strictPlatform() {
        return directory -> {
            if (directory == null) {
                throw new ArchiveValidationException("DIRECTORY_DURABILITY_NOT_VERIFIED",
                        "directory durability barrier requires a parent directory");
            }
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException | RuntimeException unsupported) {
                throw new ArchiveValidationException("DIRECTORY_DURABILITY_NOT_VERIFIED",
                        "platform/filesystem cannot verify directory durability for "
                                + directory.toAbsolutePath().normalize(), unsupported);
            }
        };
    }
}
