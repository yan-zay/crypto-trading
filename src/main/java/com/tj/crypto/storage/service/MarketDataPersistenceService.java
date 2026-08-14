package com.tj.crypto.storage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.risk.KillSwitch;
import com.tj.crypto.storage.converter.BarEventConverter;
import com.tj.crypto.storage.entity.BarEventDO;
import com.tj.crypto.storage.mapper.BarEventMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable bar persistence buffer.
 *
 * <p>A finalized bar is synchronously appended to a local fsync'd spool before this method
 * returns. Database delivery remains asynchronous and idempotent. A crash after the database
 * upsert but before spool truncation only causes a harmless replay. Spool corruption, disk
 * failure, database failure, or an exhausted in-memory recovery buffer activates HALT instead
 * of silently dropping market data.</p>
 */
@Slf4j
@Service
public class MarketDataPersistenceService {
    private static final int MAX_RECOVERY_BUFFER_SIZE = 100_000;

    private final BarEventMapper barEventMapper;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final KillSwitch killSwitch;
    private final Path spoolPath;
    private final int batchSize;
    private final List<BarEventDO> buffer = new ArrayList<>();
    private boolean overflowed;

    public MarketDataPersistenceService(BarEventMapper barEventMapper,
                                        @Qualifier("tjTaskExecutor") TaskExecutor tjTaskExecutor,
                                        ObjectMapper objectMapper,
                                        KillSwitch killSwitch,
                                        @Value("${crypto.persistence.bar-spool-file:target/spool/finalized-bars.jsonl}")
                                        String spoolFile,
                                        @Value("${crypto.persistence.bar-batch-size:100}") int batchSize) {
        this.barEventMapper = barEventMapper;
        this.taskExecutor = tjTaskExecutor;
        this.objectMapper = objectMapper;
        this.killSwitch = killSwitch;
        this.spoolPath = Path.of(spoolFile).toAbsolutePath().normalize();
        if (batchSize <= 0 || batchSize > MAX_RECOVERY_BUFFER_SIZE) {
            throw new IllegalArgumentException("bar-batch-size must be between 1 and "
                    + MAX_RECOVERY_BUFFER_SIZE);
        }
        this.batchSize = batchSize;
    }

    /** Replays an unacknowledged spool before the application can report readiness. */
    @PostConstruct
    public void recoverSpool() {
        synchronized (buffer) {
            try {
                Path parent = spoolPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                if (!Files.exists(spoolPath)) return;
                long recovered = 0;
                try (BufferedReader reader = Files.newBufferedReader(spoolPath, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        buffer.add(objectMapper.readValue(line, BarEventDO.class));
                        recovered++;
                        if (buffer.size() >= batchSize) {
                            barEventMapper.upsertBatch(new ArrayList<>(buffer));
                            buffer.clear();
                        }
                    }
                }
                if (recovered > 0) {
                    if (!buffer.isEmpty()) {
                        barEventMapper.upsertBatch(new ArrayList<>(buffer));
                        buffer.clear();
                    }
                    truncateSpool();
                    overflowed = false;
                    log.warn("Recovered {} finalized bars from durable spool {}", recovered, spoolPath);
                }
            } catch (IOException | RuntimeException e) {
                halt("Finalized bar spool recovery failed", e);
                throw new IllegalStateException("Finalized bar spool recovery failed", e);
            }
        }
    }

    /**
     * Legacy name retained for callers. Enqueue is now synchronously durable; only the database
     * batch flush is asynchronous.
     */
    public void persistBarAsync(BarEvent event) {
        BarEventDO bar = BarEventConverter.toDO(event);
        boolean flushRequired;
        synchronized (buffer) {
            appendDurably(bar);
            if (overflowed || buffer.size() >= MAX_RECOVERY_BUFFER_SIZE) {
                overflowed = true;
                halt("Finalized bar recovery buffer exhausted", null);
                throw new IllegalStateException("Finalized bar recovery buffer exhausted; event is safe in spool");
            }
            buffer.add(bar);
            flushRequired = buffer.size() >= batchSize;
        }
        if (flushRequired) taskExecutor.execute(this::flush);
    }

    public void flush() {
        synchronized (buffer) {
            if (buffer.isEmpty() || overflowed) return;
            try {
                flushBufferOrThrow();
            } catch (RuntimeException e) {
                halt("Finalized bar database flush failed", e);
                throw e;
            }
        }
    }

    @Scheduled(fixedDelayString = "${crypto.persistence.bar-flush-interval-ms:1000}")
    public void scheduledFlush() {
        flush();
    }

    int pendingCount() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    Path spoolPath() {
        return spoolPath;
    }

    private void flushBufferOrThrow() {
        if (buffer.isEmpty()) return;
        List<BarEventDO> batch = new ArrayList<>(buffer);
        barEventMapper.upsertBatch(batch);
        truncateSpool();
        buffer.clear();
        log.debug("Flushed {} finalized bars to database", batch.size());
    }

    private void appendDurably(BarEventDO bar) {
        try {
            Path parent = spoolPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            byte[] line = (objectMapper.writeValueAsString(bar) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(spoolPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND, StandardOpenOption.SYNC)) {
                ByteBuffer data = ByteBuffer.wrap(line);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
        } catch (IOException | RuntimeException e) {
            halt("Finalized bar spool append failed", e);
            throw new IllegalStateException("Unable to durably spool finalized bar", e);
        }
    }

    private void truncateSpool() {
        try (FileChannel channel = FileChannel.open(spoolPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
            channel.force(true);
        } catch (IOException e) {
            // Database upserts are idempotent. Leaving the spool intact is safer than pretending ack.
            throw new IllegalStateException("Database committed but bar spool acknowledgement failed", e);
        }
    }

    private void halt(String reason, Throwable error) {
        try {
            killSwitch.activate(KillSwitch.Mode.HALT);
        } catch (RuntimeException persistenceFailure) {
            if (error != null) persistenceFailure.addSuppressed(error);
            log.error("{}; persistent KillSwitch update also failed", reason, persistenceFailure);
            return;
        }
        if (error == null) log.error(reason);
        else log.error(reason, error);
    }
}
