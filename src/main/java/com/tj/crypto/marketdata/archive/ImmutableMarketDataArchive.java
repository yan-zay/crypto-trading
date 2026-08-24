package com.tj.crypto.marketdata.archive;

import com.tj.crypto.marketdata.model.ArchivableMarketData;

import java.nio.file.Path;
import java.util.List;

/** 对象存储可替换端口；当前实现为本地文件系统的不可变 Parquet 归档。 */
public interface ImmutableMarketDataArchive {
    ArchiveCommit archive(ArchiveBatch batch);

    ArchiveVerification verify(Path manifestPath);

    List<ArchivableMarketData> read(Path manifestPath);
}
