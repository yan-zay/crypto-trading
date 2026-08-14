package com.tj.crypto.research.agent;

import com.tj.crypto.factor.core.FactorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 只读取 FactorRegistry 运行时注册事实。 */
@Component
@RequiredArgsConstructor
public class FactorCatalogResearchTool implements ReadOnlyResearchTool {

    private final FactorRegistry factorRegistry;

    @Override
    public ResearchToolName name() {
        return ResearchToolName.FACTOR_CATALOG;
    }

    @Override
    public String description() {
        return "读取当前进程已注册因子及显式历史切片支持情况";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public List<String> dataSources() {
        return List.of("runtime:FactorRegistry");
    }

    @Override
    public List<String> limitations() {
        return List.of("注册存在不代表因子具有样本外增量价值或足够数据质量");
    }

    @Override
    public FactorCatalogSnapshot execute() {
        List<FactorCatalogSnapshot.FactorView> factors = factorRegistry.getRegisteredFactors().stream()
                .sorted()
                .map(name -> new FactorCatalogSnapshot.FactorView(
                        name, factorRegistry.supportsBarHistory(name)))
                .toList();
        return new FactorCatalogSnapshot(factors.size(), factors);
    }
}
