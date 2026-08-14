package com.tj.crypto.research.agent;

import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** 只读取 StrategyManager 运行时注册事实。 */
@Component
@RequiredArgsConstructor
public class StrategyCatalogResearchTool implements ReadOnlyResearchTool {

    private final StrategyManager strategyManager;

    @Override
    public ResearchToolName name() {
        return ResearchToolName.STRATEGY_CATALOG;
    }

    @Override
    public String description() {
        return "读取当前进程已注册策略、启用状态和监听事件";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public List<String> dataSources() {
        return List.of("runtime:StrategyManager");
    }

    @Override
    public List<String> limitations() {
        return List.of("仅反映当前进程内的策略注册事实，不代表策略有效或已通过晋级门槛");
    }

    @Override
    public StrategyCatalogSnapshot execute() {
        List<StrategyCatalogSnapshot.StrategyView> strategies = strategyManager.getAllStrategies().stream()
                .sorted(Comparator.comparing(Strategy::name))
                .map(strategy -> new StrategyCatalogSnapshot.StrategyView(
                        strategy.name(),
                        strategyManager.isStrategyEnabled(strategy.name()),
                        strategy.listenedEvents().stream()
                                .map(Class::getSimpleName)
                                .sorted()
                                .toList()))
                .toList();
        int enabledCount = (int) strategies.stream().filter(StrategyCatalogSnapshot.StrategyView::enabled).count();
        return new StrategyCatalogSnapshot(strategies.size(), enabledCount, strategies);
    }
}
