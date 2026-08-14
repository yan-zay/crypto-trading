package com.tj.crypto.research.export;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crypto.dataset-export")
public class DatasetExportProperties {
    private String directory = "target/exports";
    private int maxRows = 1_000_000;
}
