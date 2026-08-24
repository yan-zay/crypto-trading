package com.tj.crypto.research.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

/** Writes canonical evaluation reports by digest and never overwrites existing evidence. */
public final class ImmutableAgentEvalReportStore {
    public static final String FILE_SUFFIX = ".agent-eval.json";

    private final ObjectMapper mapper;

    public ImmutableAgentEvalReportStore() {
        this.mapper = AgentEvalHashing.mapper();
    }

    public Path write(Path directory, AgentEvalReport report) {
        Path evidenceDirectory = requireDirectory(directory);
        if (report == null) {
            throw new AgentEvalException("MISSING_EVALUATION_REPORT", "evaluation report is required");
        }
        byte[] canonical = AgentEvalHashing.canonicalBytes(report);
        Path target = evidenceDirectory.resolve(report.reportSha256() + FILE_SUFFIX).normalize();
        if (!target.getParent().equals(evidenceDirectory)) {
            throw new AgentEvalException("INVALID_EVALUATION_PATH", "report target escaped evidence directory");
        }
        try {
            Files.write(target, canonical, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return target;
        } catch (FileAlreadyExistsException collision) {
            requireIdenticalExisting(target, canonical);
            return target;
        } catch (IOException error) {
            throw new AgentEvalException("IMMUTABLE_REPORT_WRITE_FAILED",
                    "Cannot create immutable Agent evaluation report", error);
        }
    }

    public AgentEvalReport read(Path reportPath) {
        Path path = requireRegularNonSymlink(reportPath);
        byte[] bytes;
        AgentEvalReport report;
        try {
            bytes = Files.readAllBytes(path);
            report = mapper.readValue(bytes, AgentEvalReport.class);
        } catch (AgentEvalException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new AgentEvalException("INVALID_IMMUTABLE_REPORT",
                    "Cannot parse strict Agent evaluation report", error);
        }
        String expectedName = report.reportSha256() + FILE_SUFFIX;
        if (!expectedName.equals(path.getFileName().toString())) {
            throw new AgentEvalException("REPORT_ADDRESS_MISMATCH",
                    "report filename must equal its content address");
        }
        byte[] canonical = AgentEvalHashing.canonicalBytes(report);
        if (!MessageDigest.isEqual(bytes, canonical)) {
            throw new AgentEvalException("NON_CANONICAL_REPORT",
                    "stored Agent evaluation report is not canonical JSON");
        }
        return report;
    }

    private Path requireDirectory(Path directory) {
        if (directory == null) {
            throw new AgentEvalException("INVALID_EVALUATION_DIRECTORY", "evidence directory is required");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentEvalException("INVALID_EVALUATION_DIRECTORY",
                    "evidence directory must exist and must not be a symbolic link");
        }
        return normalized;
    }

    private Path requireRegularNonSymlink(Path path) {
        if (path == null) {
            throw new AgentEvalException("MISSING_EVALUATION_REPORT", "report path is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentEvalException("MISSING_EVALUATION_REPORT",
                    "report must be a regular non-symlink file");
        }
        return normalized;
    }

    private void requireIdenticalExisting(Path target, byte[] canonical) {
        Path existing = requireRegularNonSymlink(target);
        try {
            if (!MessageDigest.isEqual(Files.readAllBytes(existing), canonical)) {
                throw new AgentEvalException("CONTENT_ADDRESS_COLLISION",
                        "existing report bytes differ at the requested content address");
            }
        } catch (AgentEvalException error) {
            throw error;
        } catch (IOException error) {
            throw new AgentEvalException("IMMUTABLE_REPORT_READ_FAILED",
                    "Cannot verify existing immutable Agent evaluation report", error);
        }
    }
}
