package com.tj.crypto.research.agent.sandbox;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** 使用受控内部线程执行纯计算并在超时时取消；不会创建外部操作系统进程。 */
@Component
public class TimedSandboxTaskRunner implements SandboxTaskRunner {

    private final ExecutorService executor;

    public TimedSandboxTaskRunner() {
        this(Executors.newFixedThreadPool(2, new SandboxThreadFactory()));
    }

    TimedSandboxTaskRunner(ExecutorService executor) {
        if (executor == null) throw new IllegalArgumentException("executor is required");
        this.executor = executor;
    }

    @Override
    public <T> T run(Duration timeout, Callable<T> task) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || task == null) {
            throw new IllegalArgumentException("Positive timeout and task are required");
        }
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SandboxTimeoutException("Sandbox tool exceeded its invocation timeout", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SandboxTimeoutException("Sandbox tool was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Sandbox tool failed", cause);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static final class SandboxThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "agent-sandbox-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) -> { });
            return thread;
        }
    }
}
