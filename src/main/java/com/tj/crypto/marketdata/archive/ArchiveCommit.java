package com.tj.crypto.marketdata.archive;

import java.nio.file.Path;

/** 成功提交的不可变批次。 */
public record ArchiveCommit(
        ArchiveManifest manifest,
        Path manifestPath,
        Path dataPath,
        String manifestSha256,
        boolean alreadyPresent
) {}
