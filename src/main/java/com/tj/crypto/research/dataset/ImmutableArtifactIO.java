package com.tj.crypto.research.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Read/write helpers that never overwrite research evidence. */
public final class ImmutableArtifactIO {
    private final ObjectMapper objectMapper;

    public ImmutableArtifactIO() {
        this(ResearchJson.mapper());
    }

    public ImmutableArtifactIO(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T read(Path path, Class<T> type) {
        Path artifact = regularNonSymlink(path);
        try {
            return objectMapper.readValue(artifact.toFile(), type);
        } catch (IOException | RuntimeException error) {
            throw new DatasetValidationException("INVALID_JSON_ARTIFACT",
                    "Cannot read strict JSON artifact " + artifact.getFileName() + ": " + error.getMessage());
        }
    }

    public void writeNew(Path path, Object value) {
        Path target = path.toAbsolutePath().normalize();
        try {
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new DatasetValidationException("INVALID_ARTIFACT_DIRECTORY", "artifact parent directory must exist");
            }
            try (OutputStream output = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, value);
            }
        } catch (DatasetValidationException error) {
            throw error;
        } catch (IOException error) {
            throw new DatasetValidationException("IMMUTABLE_WRITE_FAILED",
                    "Cannot create immutable artifact " + target.getFileName() + ": " + error.getMessage());
        }
    }

    public static String sha256(Path path) {
        Path artifact = regularNonSymlink(path);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(artifact)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException error) {
            throw new DatasetValidationException("ARTIFACT_READ_FAILED", "Cannot hash artifact: " + error.getMessage());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static Path regularNonSymlink(Path path) {
        if (path == null) throw new DatasetValidationException("MISSING_ARTIFACT", "artifact path is required");
        Path artifact = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new DatasetValidationException("MISSING_ARTIFACT", "artifact must be a regular non-symlink file: " + artifact);
        }
        return artifact;
    }
}
