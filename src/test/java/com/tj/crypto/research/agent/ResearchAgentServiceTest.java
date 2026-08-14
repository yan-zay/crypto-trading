package com.tj.crypto.research.agent;

import com.tj.crypto.admin.ResearchAgentAdminController;
import com.tj.crypto.factor.core.FactorRegistry;
import com.tj.crypto.marketdata.model.BarEvent;
import com.tj.crypto.strategy.core.Strategy;
import com.tj.crypto.strategy.core.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchAgentServiceTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-13T00:00:00Z");

    private StrategyManager strategyManager;
    private FactorRegistry factorRegistry;
    private List<ReadOnlyResearchTool> tools;
    private ResearchAgentService service;

    @BeforeEach
    void setUp() {
        strategyManager = mock(StrategyManager.class);
        factorRegistry = mock(FactorRegistry.class);

        Strategy beta = mock(Strategy.class);
        when(beta.name()).thenReturn("Beta");
        when(beta.listenedEvents()).thenReturn(Set.of(BarEvent.class));
        Strategy alpha = mock(Strategy.class);
        when(alpha.name()).thenReturn("Alpha");
        when(alpha.listenedEvents()).thenReturn(Set.of(BarEvent.class));
        when(strategyManager.getAllStrategies()).thenReturn(List.of(beta, alpha));
        when(strategyManager.isStrategyEnabled("Alpha")).thenReturn(true);
        when(strategyManager.isStrategyEnabled("Beta")).thenReturn(false);

        when(factorRegistry.getRegisteredFactors()).thenReturn(List.of("RSI_14", "SMA_20"));
        when(factorRegistry.supportsBarHistory("RSI_14")).thenReturn(true);
        when(factorRegistry.supportsBarHistory("SMA_20")).thenReturn(true);

        tools = List.of(
                new StrategyCatalogResearchTool(strategyManager),
                new FactorCatalogResearchTool(factorRegistry),
                new RuntimeResearchSummaryTool(strategyManager, factorRegistry));
        service = new ResearchAgentService(
                tools,
                Clock.fixed(GENERATED_AT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("能力清单仅包含显式只读白名单并披露 L0 边界")
    void shouldExposeOnlyExplicitReadOnlyCapabilities() {
        ResearchAgentEnvelope response = service.capabilities();

        assertThat(response.agentLevel()).isEqualTo("L0");
        assertThat(response.modelConnected()).isFalse();
        assertThat(response.readOnly()).isTrue();
        assertThat(response.deterministic()).isTrue();
        assertThat(response.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(response.dataSources()).isNotEmpty();
        assertThat(response.limitations())
                .anyMatch(value -> value.contains("无凭据"))
                .anyMatch(value -> value.contains("不是投资建议"));

        @SuppressWarnings("unchecked")
        List<ResearchToolDescriptor> descriptors = (List<ResearchToolDescriptor>) response.result();
        assertThat(descriptors)
                .extracting(ResearchToolDescriptor::name)
                .containsExactly(
                        ResearchToolName.STRATEGY_CATALOG,
                        ResearchToolName.FACTOR_CATALOG,
                        ResearchToolName.RUNTIME_RESEARCH_SUMMARY);
        assertThat(descriptors).allMatch(ResearchToolDescriptor::readOnly);
    }

    @Test
    @DisplayName("研究摘要只读运行时 registry 且输出排序稳定")
    void shouldBuildDeterministicReadOnlyRuntimeSummary() {
        ResearchAgentEnvelope response = service.query(
                new ResearchAgentQuery(ResearchToolName.RUNTIME_RESEARCH_SUMMARY));

        assertThat(response.operation()).isEqualTo("RUNTIME_RESEARCH_SUMMARY");
        assertThat(response.dataSources())
                .containsExactly("runtime:StrategyManager", "runtime:FactorRegistry");
        RuntimeResearchSummary summary = (RuntimeResearchSummary) response.result();
        assertThat(summary.registeredStrategyCount()).isEqualTo(2);
        assertThat(summary.enabledStrategyCount()).isEqualTo(1);
        assertThat(summary.registeredFactorCount()).isEqualTo(2);
        assertThat(summary.enabledStrategies()).containsExactly("Alpha");
        assertThat(summary.registeredFactors()).containsExactly("RSI_14", "SMA_20");

        verify(strategyManager, never()).enableStrategy(anyString());
        verify(strategyManager, never()).disableStrategy(anyString());
    }

    @Test
    @DisplayName("策略和因子目录来自运行时事实且不暴露策略配置")
    void shouldReturnTypedCatalogSnapshots() {
        StrategyCatalogSnapshot strategySnapshot = (StrategyCatalogSnapshot) service.query(
                new ResearchAgentQuery(ResearchToolName.STRATEGY_CATALOG)).result();
        FactorCatalogSnapshot factorSnapshot = (FactorCatalogSnapshot) service.query(
                new ResearchAgentQuery(ResearchToolName.FACTOR_CATALOG)).result();

        assertThat(strategySnapshot.strategies())
                .extracting(StrategyCatalogSnapshot.StrategyView::name)
                .containsExactly("Alpha", "Beta");
        assertThat(strategySnapshot.enabledCount()).isEqualTo(1);
        assertThat(factorSnapshot.factors())
                .extracting(FactorCatalogSnapshot.FactorView::name)
                .containsExactly("RSI_14", "SMA_20");
        assertThat(factorSnapshot.factors())
                .allMatch(FactorCatalogSnapshot.FactorView::supportsExplicitBarHistory);
    }

    @Test
    @DisplayName("非只读、重复或缺失工具都在启动时 fail-closed")
    void shouldRejectToolRegistryBypassAttempts() {
        ReadOnlyResearchTool writeTool = stubTool(ResearchToolName.STRATEGY_CATALOG, false);
        ReadOnlyResearchTool duplicate = stubTool(ResearchToolName.STRATEGY_CATALOG, true);

        assertThatThrownBy(() -> new ResearchAgentService(List.of(writeTool)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> new ResearchAgentService(
                List.of(tools.get(0), duplicate, tools.get(1), tools.get(2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new ResearchAgentService(List.of(tools.get(0))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing required");
    }

    @Test
    @DisplayName("空工具选择被拒绝且白名单没有资金写操作")
    void shouldRejectMissingOrFinancialWriteOperations() {
        assertThatThrownBy(() -> service.query(new ResearchAgentQuery(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitelisted");
        assertThat(Arrays.stream(ResearchToolName.values()).map(Enum::name))
                .noneMatch(name -> name.contains("ORDER")
                        || name.contains("CREDENTIAL")
                        || name.contains("KILL_SWITCH")
                        || name.contains("CONFIG"));
    }

    @Test
    @DisplayName("L0 入口和工具没有执行、私有交易、熔断或配置写依赖")
    void shouldHaveNoForbiddenWriteDependencies() {
        List<Class<?>> boundaryTypes = List.of(
                ResearchAgentAdminController.class,
                ResearchAgentService.class,
                StrategyCatalogResearchTool.class,
                FactorCatalogResearchTool.class,
                RuntimeResearchSummaryTool.class);

        assertThat(boundaryTypes.stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                .map(Field::getType)
                .map(Class::getName))
                .noneMatch(type -> type.contains(".execution.")
                        || type.contains(".venue.")
                        || type.contains("KillSwitch")
                        || type.contains("AuthService")
                        || type.contains("ConfigVersionService")
                        || type.toLowerCase().contains("credential"));
    }

    private ReadOnlyResearchTool stubTool(ResearchToolName name, boolean readOnly) {
        return new ReadOnlyResearchTool() {
            @Override
            public ResearchToolName name() {
                return name;
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public boolean readOnly() {
                return readOnly;
            }

            @Override
            public List<String> dataSources() {
                return List.of("test");
            }

            @Override
            public List<String> limitations() {
                return List.of("test only");
            }

            @Override
            public Object execute() {
                return "test";
            }
        };
    }
}
