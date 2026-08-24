package com.tj.crypto.marketdata.archive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Unit-test recorder only; it does not claim to perform a host filesystem fsync. */
final class RecordingDirectoryDurabilityBarrier implements DirectoryDurabilityBarrier {
    private final List<Path> calls = new ArrayList<>();
    private boolean failNext;

    @Override
    public void force(Path directory) {
        calls.add(directory.toAbsolutePath().normalize());
        if (failNext) {
            failNext = false;
            throw new ArchiveValidationException("DIRECTORY_DURABILITY_NOT_VERIFIED",
                    "test barrier rejected directory durability");
        }
    }

    List<Path> calls() {
        return List.copyOf(calls);
    }

    void failNext() {
        failNext = true;
    }
}
