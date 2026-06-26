package com.tj.crypto.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 因子信息 DTO。
 * 描述一个已注册的因子计算器。
 */
@Data
@Builder
public class FactorInfoDTO {

    /** 因子名称，如 "SMA_20", "RSI_14" */
    private String name;
}
