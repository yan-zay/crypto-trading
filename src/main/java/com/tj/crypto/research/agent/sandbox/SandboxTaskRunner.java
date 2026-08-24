package com.tj.crypto.research.agent.sandbox;

import java.time.Duration;
import java.util.concurrent.Callable;

/** 仅运行进程内纯计算任务；Agent 不获得 Executor、shell 或 ProcessBuilder。 */
public interface SandboxTaskRunner {
    <T> T run(Duration timeout, Callable<T> task);
}
