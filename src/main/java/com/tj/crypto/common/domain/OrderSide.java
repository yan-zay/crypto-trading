package com.tj.crypto.common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单方向枚举。
 * 用于爆仓事件中的多/空方向。
 */
@Getter
@AllArgsConstructor
public enum OrderSide {
    LONG(1, "多"),
    SHORT(2, "空"),
    ;

    private final int code;
    private final String displayName;

    /**
     * 从 Coinglass side 整数值解析。
     * 1 = LONG, 2 = SHORT。
     */
    public static OrderSide fromCode(int code) {
        for (OrderSide side : values()) {
            if (side.code == code) {
                return side;
            }
        }
        throw new IllegalArgumentException("Unknown order side code: " + code);
    }
}
