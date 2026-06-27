package com.tj.crypto.admin.application;

import com.tj.crypto.admin.domain.ConfigType;
import org.springframework.context.ApplicationEvent;

/**
 * 配置发布事件。
 * 在配置成功发布或回滚后发布，触发运行态同步。
 */
public class ConfigPublishedEvent extends ApplicationEvent {

    private final ConfigType configType;
    private final String configKey;

    public ConfigPublishedEvent(Object source, ConfigType configType, String configKey) {
        super(source);
        this.configType = configType;
        this.configKey = configKey;
    }

    public ConfigType getConfigType() {
        return configType;
    }

    public String getConfigKey() {
        return configKey;
    }
}
