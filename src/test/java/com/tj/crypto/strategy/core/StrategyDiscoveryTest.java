package com.tj.crypto.strategy.core;

import com.tj.crypto.strategy.config.StrategyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyDiscoveryTest {

    @Test
    @DisplayName("application.yml 声明的六个策略均可被 Spring 显式发现")
    void shouldDiscoverAllConfiguredStrategies() throws IOException {
        StrategyProperties configuredProperties = loadStrategyProperties();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(StrategyProperties.class, () -> configuredProperties);
            context.register(StrategyManager.class);
            context.scan("com.tj.crypto.strategy.impl");
            context.refresh();

            Map<String, Strategy> strategyBeans = context.getBeansOfType(Strategy.class);
            Set<String> discoveredNames = strategyBeans.values().stream()
                    .map(Strategy::name)
                    .collect(Collectors.toSet());

            Set<String> configuredNames = configuredProperties.getConfigs().entrySet().stream()
                    .filter(entry -> entry.getValue().isEnabled())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            assertThat(configuredNames).hasSize(6);
            assertThat(discoveredNames).isEqualTo(configuredNames);
            assertThat(context.getBean(StrategyManager.class).getAllStrategies()).hasSize(6);
        }
    }

    private StrategyProperties loadStrategyProperties() throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(propertySources::addLast);

        return new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("crypto.strategy", Bindable.of(StrategyProperties.class))
                .orElseThrow(() -> new IllegalStateException("crypto.strategy is missing from application.yml"));
    }
}
