package com.tj.crypto.research.agent;

import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 聚合运行时注册表，生成不包含模型推断的研究平台摘要。 */
@Component
@RequiredArgsConstructor
public class RuntimeResearchSummaryTool implements ReadOnlyResearchTool {

    private final StrategyManager strategyManager;
    private final FactorRegistry factorRegistry;

    @Override
    public ResearchToolName name() {
        return ResearchToolName.RUNTIME_RESEARCH_SUMMARY;
    }

    @Override
    public String description() {
        return "汇总当前策略和因子运行事实，供人工研究与审计使用";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public List<String> dataSources() {
        return List.of("runtime:StrategyManager", "runtime:FactorRegistry");
    }

    @Override
    public List<String> limitations() {
        return List.of(
                "摘要不读取交易所私有数据，也不生成、批准或执行订单",
                "摘要不是投资建议，不能替代样本外研究、法律意见或持续运行验收");
    }

    @Override
    public RuntimeResearchSummary execute() {
        List<Strategy> strategies = strategyManager.getAllStrategies();
        List<String> enabledStrategies = strategies.stream()
                .map(Strategy::name)
                .filter(strategyManager::isStrategyEnabled)
                .sorted()
                .toList();
        List<String> factors = factorRegistry.getRegisteredFactors().stream().sorted().toList();
        return new RuntimeResearchSummary(
                strategies.size(),
                enabledStrategies.size(),
                factors.size(),
                enabledStrategies,
                factors,
                "runtime registry inventory only");
    }
}
