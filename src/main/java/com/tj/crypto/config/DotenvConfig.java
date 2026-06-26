package com.tj.crypto.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * .env 文件加载器。
 * 在 Spring 环境初始化后加载项目根目录的 .env 文件。
 * 优先级低于系统环境变量（不会覆盖已有的环境变量）。
 */
@Slf4j
public class DotenvConfig implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Resource resource = new FileSystemResource(".env");
        if (!resource.exists()) {
            return;
        }

        Map<String, Object> envVars = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eqIndex = line.indexOf('=');
                if (eqIndex > 0) {
                    String key = line.substring(0, eqIndex).trim();
                    String value = line.substring(eqIndex + 1).trim();
                    // 不覆盖已有的环境变量
                    if (environment.getProperty(key) == null && System.getenv(key) == null) {
                        envVars.put(key, value);
                    }
                }
            }
            if (!envVars.isEmpty()) {
                environment.getPropertySources().addLast(new MapPropertySource("dotenv", envVars));
                log.info("Loaded {} variables from .env file", envVars.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load .env file: {}", e.getMessage());
        }
    }
}
