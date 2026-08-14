package com.tj.crypto.pojo.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Author zay
 * @Date 2025/9/18 17:52
 */
@Data
public class LiquidationOrder {

    @JsonAlias({"base_asset"})
    private String baseAsset;
    @JsonAlias({"exchange", "ex_name"})
    private String exName;
    private BigDecimal price;
    private int side;
    private String symbol;
    private long time;
    @JsonAlias({"volume_usd", "vol_usd"})
    private BigDecimal volUsd;
}
