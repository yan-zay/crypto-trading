package com.tj.crypto.research.export;

import com.tj.crypto.reliability.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Keeps export manifest state durable while artifact I/O runs outside database transactions. */
@Service
@RequiredArgsConstructor
public class DatasetExportManifestService {
    private final DatasetExportMapper mapper;
    private final OutboxService outboxService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(DatasetExportDO manifest) {
        mapper.insert(manifest);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(DatasetExportDO manifest) {
        mapper.complete(manifest);
        outboxService.append("DATASET_EXPORT", manifest.getExportId(), "DATASET_EXPORT_COMPLETED",
                Map.of("exportId", manifest.getExportId(), "rowCount", manifest.getRowCount(),
                        "checksum", manifest.getChecksum()), null, manifest.getCompletedAtMs());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(DatasetExportDO manifest) {
        mapper.fail(manifest);
    }
}
