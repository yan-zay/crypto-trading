package com.tj.crypto.research.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.Timeframe;
import com.tj.crypto.storage.service.BarEventPersistenceService;
import com.tj.crypto.storage.service.DataLineageService;
import com.tj.crypto.trading.paper.PaperTradingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Creates checksummed research artifacts without coupling the core to cloud object storage. */
@Service
@RequiredArgsConstructor
public class DatasetExportService {
    private final DatasetExportMapper mapper;
    private final DatasetExportManifestService manifestService;
    private final DatasetExportProperties properties;
    private final BarEventPersistenceService barService;
    private final DataLineageService lineageService;
    private final PaperTradingQueryService paperQueryService;
    private final ObjectMapper objectMapper;

    public DatasetExportDO export(DatasetExportRequest request, String operator) {
        long now = System.currentTimeMillis();
        DatasetRows rows = loadRows(request);
        if (rows.values().size() > properties.getMaxRows()) {
            throw new IllegalArgumentException("Export exceeds maxRows=" + properties.getMaxRows());
        }
        DatasetExportDO manifest = new DatasetExportDO();
        manifest.setExportId(UUID.randomUUID().toString());
        manifest.setExportType(request.type().name());
        manifest.setFormat(request.format().name());
        manifest.setRequestJson(json(request));
        manifest.setStatus("RUNNING");
        manifest.setRowCount(0L);
        manifest.setDataVersion(rows.dataVersion());
        manifest.setSchemaVersion("v1");
        manifest.setCreatedBy(operator);
        manifest.setCreatedAtMs(now);
        manifestService.create(manifest);
        try {
            Path path = artifactPath(manifest);
            Files.createDirectories(path.getParent());
            write(path, request.format(), rows.values());
            manifest.setStatus("COMPLETED");
            manifest.setRowCount((long) rows.values().size());
            manifest.setChecksum(sha256(path));
            manifest.setArtifactPath(path.toString());
            manifest.setCompletedAtMs(System.currentTimeMillis());
            manifestService.complete(manifest);
            return manifest;
        } catch (IOException | RuntimeException e) {
            manifest.setStatus("FAILED");
            manifest.setErrorMessage(truncate(e.getMessage()));
            manifest.setCompletedAtMs(System.currentTimeMillis());
            manifestService.fail(manifest);
            throw new IllegalStateException("Dataset export failed", e);
        }
    }

    public List<DatasetExportDO> recent(int limit) {
        return mapper.selectRecent(Math.max(1, Math.min(limit, 500)));
    }

    public FileSystemResource resource(String exportId) {
        DatasetExportDO manifest = mapper.select(exportId);
        if (manifest == null || !"COMPLETED".equals(manifest.getStatus())) {
            throw new IllegalArgumentException("Export is not available: " + exportId);
        }
        Path root = Path.of(properties.getDirectory()).toAbsolutePath().normalize();
        Path artifact = Path.of(manifest.getArtifactPath()).toAbsolutePath().normalize();
        if (!artifact.startsWith(root) || !Files.isRegularFile(artifact)) {
            throw new IllegalStateException("Export artifact path is invalid");
        }
        return new FileSystemResource(artifact);
    }

    private DatasetRows loadRows(DatasetExportRequest request) {
        return switch (request.type()) {
            case CANONICAL_BARS -> loadBars(request);
            case PAPER_TRADES -> new DatasetRows(new ArrayList<>(paperQueryService.trades(request.accountId(),
                    properties.getMaxRows())), "paper-trades-v1");
            case OMS_FILLS -> new DatasetRows(new ArrayList<>(paperQueryService.fills(request.accountId(),
                    properties.getMaxRows())), "oms-fills-v1");
            case ACCOUNT_LEDGER -> new DatasetRows(new ArrayList<>(paperQueryService.ledger(request.accountId(),
                    properties.getMaxRows())), "account-ledger-v1");
        };
    }

    private DatasetRows loadBars(DatasetExportRequest request) {
        if (request.exchange() == null || request.marketType() == null
                || request.symbol() == null || request.timeframe() == null
                || request.from() == null || request.to() == null) {
            throw new IllegalArgumentException("Canonical bar export requires instrument, timeframe, from and to");
        }
        Instrument instrument = Instrument.of(request.exchange(), request.marketType(), request.symbol());
        Timeframe timeframe = Timeframe.fromCode(request.timeframe());
        List<?> bars = barService.loadByTimeRange(instrument, timeframe, request.from(), request.to());
        String version = lineageService.getDataVersion(instrument, timeframe, request.from(), request.to());
        return new DatasetRows(bars, version);
    }

    private void write(Path path, DatasetExportRequest.ExportFormat format, List<?> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            if (format == DatasetExportRequest.ExportFormat.JSONL) {
                for (Object row : rows) {
                    writer.write(objectMapper.writeValueAsString(row));
                    writer.newLine();
                }
                return;
            }
            writeCsv(writer, rows);
        }
    }

    private void writeCsv(BufferedWriter writer, List<?> rows) throws IOException {
        if (rows.isEmpty()) return;
        List<JsonNode> nodes = rows.stream()
                .map(row -> objectMapper.<JsonNode>valueToTree(row))
                .toList();
        Set<String> headerSet = new LinkedHashSet<>();
        for (JsonNode node : nodes) node.fieldNames().forEachRemaining(headerSet::add);
        List<String> headers = List.copyOf(headerSet);
        writer.write(String.join(",", headers.stream().map(this::csv).toList()));
        writer.newLine();
        for (JsonNode node : nodes) {
            List<String> values = new ArrayList<>();
            for (String header : headers) {
                JsonNode value = node.get(header);
                values.add(csv(value == null || value.isNull() ? "" : value.isValueNode()
                        ? value.asText() : value.toString()));
            }
            writer.write(String.join(",", values));
            writer.newLine();
        }
    }

    private Path artifactPath(DatasetExportDO manifest) {
        String extension = "CSV".equals(manifest.getFormat()) ? ".csv" : ".jsonl";
        return Path.of(properties.getDirectory()).toAbsolutePath().normalize()
                .resolve(manifest.getExportId() + extension);
    }

    private String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Export request is not serializable", e);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String truncate(String value) {
        if (value == null) return "unknown export failure";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record DatasetRows(List<?> values, String dataVersion) {}
}
