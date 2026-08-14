package com.tj.crypto.admin;

import com.tj.crypto.admin.dto.BacktestResultDTO;
import com.tj.crypto.storage.service.BacktestResultPersistenceService;
import com.tj.crypto.backtest.report.PersistedBacktestReportExporter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only API for persisted backtest runs. */
@RestController
@RequestMapping("/api/admin/backtest-results")
@RequiredArgsConstructor
public class BacktestResultAdminController {
    private final BacktestResultPersistenceService service;
    private final PersistedBacktestReportExporter reportExporter;

    @GetMapping
    public List<BacktestResultDTO> list(@RequestParam(defaultValue = "100") int limit) {
        return service.recent(limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BacktestResultDTO> get(@PathVariable String id) {
        BacktestResultDTO result = service.find(id);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/research-metadata")
    public ResponseEntity<Object> researchMetadata(@PathVariable String id) {
        Object metadata = service.researchMetadata(id);
        return metadata == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(metadata);
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<String> report(
            @PathVariable String id,
            @RequestParam(defaultValue = "json") String format) {
        BacktestResultDTO result = service.find(id);
        if (result == null) return ResponseEntity.notFound().build();
        var artifact = reportExporter.export(result, format);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.mediaType() + ";charset=UTF-8"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.filename(), java.nio.charset.StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(artifact.content());
    }
}
