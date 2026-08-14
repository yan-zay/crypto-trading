package com.tj.crypto.backtest.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tj.crypto.admin.application.BacktestApplicationService;
import com.tj.crypto.backtest.engine.BacktestCancelledException;
import com.tj.crypto.backtest.engine.BacktestExecutionContext;
import com.tj.crypto.backtest.engine.BacktestProgressMonitor;
import com.tj.crypto.observability.slo.SloName;
import com.tj.crypto.observability.slo.TradingSloService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Durable job command service and worker-side execution orchestration. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestJobService {
    private final BacktestJobMapper mapper;
    private final BacktestApplicationService backtestService;
    private final ObjectMapper objectMapper;
    private final TradingSloService sloService;

    public BacktestJobDO submit(BacktestJobRequest request, String operator) {
        long now = System.currentTimeMillis();
        BacktestJobDO job = new BacktestJobDO();
        job.setJobId(UUID.randomUUID().toString());
        job.setJobType(request.type().name());
        job.setStatus("QUEUED");
        job.setRequestJson(write(request));
        job.setProgressPct(0);
        job.setStage("QUEUED");
        job.setRandomSeed(request.randomSeed());
        job.setCreatedBy(operator == null || operator.isBlank() ? "system" : operator);
        job.setCreatedAtMs(now);
        job.setVersion(0L);
        mapper.insert(job);
        return mapper.select(job.getJobId());
    }

    public void executeClaimed(String jobId) {
        BacktestJobDO job = require(jobId);
        JobMonitor monitor = new JobMonitor(jobId);
        try {
            BacktestJobRequest request = objectMapper.readValue(job.getRequestJson(), BacktestJobRequest.class);
            mapper.progress(jobId, 3, "LOADING_DATA", System.currentTimeMillis());
            BacktestExecutionContext.run(job.getResultId(), job.getRandomSeed(), monitor,
                    () -> run(request));
            if (mapper.complete(jobId, System.currentTimeMillis()) != 1) {
                throw new IllegalStateException("Backtest job completion state changed unexpectedly");
            }
            sloService.record(SloName.BACKTEST_JOB_COMPLETION, true, elapsed(job));
        } catch (BacktestCancelledException e) {
            mapper.finishFailure(jobId, "CANCELLED", "CANCELLED", e.getMessage(), System.currentTimeMillis());
        } catch (Exception e) {
            mapper.finishFailure(jobId, "FAILED", e.getClass().getSimpleName(),
                    truncate(e.getMessage()), System.currentTimeMillis());
            log.error("Backtest job failed: jobId={}", jobId, e);
            sloService.record(SloName.BACKTEST_JOB_COMPLETION, false, elapsed(job));
        }
    }

    private long elapsed(BacktestJobDO job) {
        long started = job.getStartedAtMs() == null ? job.getCreatedAtMs() : job.getStartedAtMs();
        return Math.max(0, System.currentTimeMillis() - started);
    }

    public BacktestJobDO cancel(String jobId) {
        require(jobId);
        if (mapper.requestCancel(jobId, System.currentTimeMillis()) != 1) {
            throw new IllegalStateException("Only queued or running jobs can be cancelled");
        }
        return mapper.select(jobId);
    }

    public BacktestJobDO find(String jobId) {
        return mapper.select(jobId);
    }

    public List<BacktestJobDO> recent(String status, int limit) {
        return mapper.selectRecent(status, Math.max(1, Math.min(limit, 500)));
    }

    private Object run(BacktestJobRequest request) {
        if (request.type() == BacktestJobType.STRATEGY) {
            return backtestService.run(request.strategyName(), request.exchange(), request.marketType(),
                    request.symbol(), request.timeframe(), request.days(), request.warmupBars(),
                    request.initialBalance(), request.autoBackfill());
        }
        return backtestService.runFactorStrategy(request.exchange(), request.marketType(), request.symbol(),
                request.timeframe(), request.days(), request.warmupBars(), request.initialBalance(),
                request.autoBackfill(), request.factorStrategy());
    }

    private BacktestJobDO require(String jobId) {
        BacktestJobDO job = mapper.select(jobId);
        if (job == null) throw new IllegalArgumentException("Unknown backtest job: " + jobId);
        return job;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Backtest job request is not serializable", e);
        }
    }

    private String truncate(String value) {
        if (value == null) return "unknown backtest failure";
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private final class JobMonitor implements BacktestProgressMonitor {
        private final String jobId;
        private volatile long lastCancelCheck;
        private volatile boolean cancelled;
        private int lastProgress = -1;

        private JobMonitor(String jobId) {
            this.jobId = jobId;
        }

        @Override
        public boolean isCancellationRequested() {
            long now = System.currentTimeMillis();
            if (!cancelled && now - lastCancelCheck >= 250) {
                cancelled = mapper.cancellationRequested(jobId) > 0;
                lastCancelCheck = now;
            }
            return cancelled;
        }

        @Override
        public void onProgress(int processed, int total) {
            int progress = total <= 0 ? 90 : 10 + (int) ((long) processed * 80 / total);
            if (progress != lastProgress) {
                mapper.progress(jobId, Math.min(progress, 90), "REPLAYING", System.currentTimeMillis());
                lastProgress = progress;
            }
        }
    }
}
