package com.tj.crypto.research.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L0 只读 Research Agent 的确定性能力路由。
 *
 * <p>它不是可下单的 LLM：不接收自由文本 prompt，不持有凭据，也没有通用反射或 HTTP 工具。
 */
@Service
public class ResearchAgentService {

    private static final Set<ResearchToolName> ALLOWED_TOOLS = EnumSet.of(
            ResearchToolName.STRATEGY_CATALOG,
            ResearchToolName.FACTOR_CATALOG,
            ResearchToolName.RUNTIME_RESEARCH_SUMMARY);
    private static final List<String> BASE_LIMITATIONS = List.of(
            "L0 仅执行固定、确定性的只读查询；当前没有连接语言模型",
            "无凭据、交易所私有 API、订单、KillSwitch、风险配置或配置发布能力",
            "结果不是投资建议，也不构成实盘、法律、Alpha 或生产可靠性验收证据");

    private final Map<ResearchToolName, ReadOnlyResearchTool> tools;
    private final Clock clock;

    @Autowired
    public ResearchAgentService(List<ReadOnlyResearchTool> discoveredTools) {
        this(discoveredTools, Clock.systemUTC());
    }

    ResearchAgentService(List<ReadOnlyResearchTool> discoveredTools, Clock clock) {
        if (discoveredTools == null || clock == null) {
            throw new IllegalArgumentException("research tools and clock are required");
        }
        EnumMap<ResearchToolName, ReadOnlyResearchTool> validated = new EnumMap<>(ResearchToolName.class);
        for (ReadOnlyResearchTool tool : discoveredTools) {
            if (tool == null || tool.name() == null) {
                throw new IllegalStateException("Research tool and name must not be null");
            }
            if (!ALLOWED_TOOLS.contains(tool.name())) {
                throw new IllegalStateException("Research tool is not explicitly allowed: " + tool.name());
            }
            if (!tool.readOnly()) {
                throw new IllegalStateException("Research tool must be explicitly read-only: " + tool.name());
            }
            if (validated.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate research tool: " + tool.name());
            }
        }
        if (!validated.keySet().equals(ALLOWED_TOOLS)) {
            EnumSet<ResearchToolName> missing = EnumSet.copyOf(ALLOWED_TOOLS);
            missing.removeAll(validated.keySet());
            throw new IllegalStateException("Missing required read-only research tools: " + missing);
        }
        this.tools = Map.copyOf(validated);
        this.clock = clock;
    }

    public ResearchAgentEnvelope capabilities() {
        List<ResearchToolDescriptor> descriptors = tools.values().stream()
                .sorted(Comparator.comparing(ReadOnlyResearchTool::name))
                .map(tool -> new ResearchToolDescriptor(
                        tool.name(), tool.description(), tool.readOnly(),
                        List.copyOf(tool.dataSources()), List.copyOf(tool.limitations())))
                .toList();
        return envelope(
                "CAPABILITIES",
                List.of("runtime:ResearchAgentService.explicitAllowlist"),
                BASE_LIMITATIONS,
                descriptors);
    }

    public ResearchAgentEnvelope query(ResearchAgentQuery query) {
        if (query == null || query.tool() == null) {
            throw new IllegalArgumentException("A whitelisted research tool is required");
        }
        ReadOnlyResearchTool tool = tools.get(query.tool());
        if (tool == null || !tool.readOnly()) {
            throw new IllegalArgumentException("Research tool is not allowed: " + query.tool());
        }
        List<String> limitations = new ArrayList<>(BASE_LIMITATIONS);
        limitations.addAll(tool.limitations());
        return envelope(tool.name().name(), tool.dataSources(), limitations, tool.execute());
    }

    /** 为拒绝的边界请求生成同样带来源、时间和限制信息的响应。 */
    public ResearchAgentEnvelope rejectedQuery() {
        return envelope(
                "QUERY_REJECTED",
                List.of("request:ResearchAgentQuery.schema"),
                BASE_LIMITATIONS,
                Map.of(
                        "accepted", false,
                        "error", "Only an exact whitelisted read-only research query is accepted"));
    }

    private ResearchAgentEnvelope envelope(
            String operation,
            List<String> dataSources,
            List<String> limitations,
            Object result) {
        return new ResearchAgentEnvelope(
                "L0",
                false,
                operation,
                true,
                true,
                Instant.now(clock),
                List.copyOf(dataSources),
                List.copyOf(limitations),
                result);
    }
}
