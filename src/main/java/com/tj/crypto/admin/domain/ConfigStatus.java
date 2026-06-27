package com.tj.crypto.admin.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 配置版本状态枚举。
 * 定义配置生命周期：DRAFT → VALIDATED → PUBLISHED → ACTIVE → ROLLED_BACK / ARCHIVED。
 */
@Getter
@AllArgsConstructor
public enum ConfigStatus {

    DRAFT("draft", "草稿"),
    VALIDATED("validated", "已验证"),
    PUBLISHED("published", "已发布"),
    ACTIVE("active", "生效中"),
    ROLLED_BACK("rolled_back", "已回滚"),
    ARCHIVED("archived", "已归档"),
    ;

    private final String code;
    private final String displayName;

    /**
     * 根据 code 查找枚举值。
     */
    public static ConfigStatus fromCode(String code) {
        for (ConfigStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown ConfigStatus code: " + code);
    }
}
