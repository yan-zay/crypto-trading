package com.tj.crypto.research.agent.policy;

import java.nio.file.Path;
import java.util.List;

/** 调用在执行前申报的全部外部资源访问；未申报访问不属于合法工具实现。 */
public record SandboxAccessRequest(
        int networkCalls,
        List<Path> fileReads,
        List<Path> fileWrites,
        long fileBytes,
        int childProcesses) {

    public SandboxAccessRequest {
        if (networkCalls < 0 || fileBytes < 0 || childProcesses < 0) {
            throw new IllegalArgumentException("Sandbox access counters cannot be negative");
        }
        fileReads = fileReads == null ? List.of() : List.copyOf(fileReads);
        fileWrites = fileWrites == null ? List.of() : List.copyOf(fileWrites);
        if (fileReads.stream().anyMatch(path -> path == null)
                || fileWrites.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException("Sandbox paths cannot be null");
        }
    }

    public static SandboxAccessRequest none() {
        return new SandboxAccessRequest(0, List.of(), List.of(), 0L, 0);
    }
}
