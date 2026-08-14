package com.tj.crypto.research.export;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DatasetExportDO {
    private String exportId;
    private String exportType;
    private String format;
    private String requestJson;
    private String status;
    private Long rowCount;
    private String checksum;
    private String dataVersion;
    private String schemaVersion;
    private String artifactPath;
    private String errorMessage;
    private String createdBy;
    private Long createdAtMs;
    private Long completedAtMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
