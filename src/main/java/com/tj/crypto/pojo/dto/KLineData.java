package com.tj.crypto.pojo.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zay
 * @Date 2025/10/13 17:42
 */
@Data
public class KLineData {

    private Long time;
    private BigDecimal quantity;
}
