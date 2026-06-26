package com.tj.crypto.pojo.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zay
 * @Date 2025/9/18 17:52
 */
@Data
public class LiquidationOrder {

    private String baseAsset;
    private String exName;
    private BigDecimal price;
    private int side;
    private String symbol;
    private long time;
    private BigDecimal volUsd;
}
