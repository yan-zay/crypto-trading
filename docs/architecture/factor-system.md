# 因子系统

> 本文档描述因子计算框架的设计、TA4J 集成和使用方式。

## 设计理念

因子（Factor）是从原始市场数据计算出的特征值，是策略决策的依据。

### 为什么需要因子系统

1. **关注点分离**：因子计算与策略逻辑分离，策略只关心"信号"，不关心"怎么算"
2. **可复用**：同一因子可被多个策略使用
3. **可测试**：因子计算可独立单元测试
4. **可扩展**：添加新因子只需实现 FactorCalculator 接口

## 架构

```
MarketEventBus → BarCache → FactorCalculator → Factor → StrategyContext → Strategy
                      ↑
              FundingRateEvent / OpenInterestEvent / LiquidationEvent
                      ↓
              衍生品因子（直接订阅事件总线）
```

## 核心组件

### Factor（因子值）

```java
public record Factor(
    String name,           // 因子名称，如 "SMA_20", "RSI_14"
    BigDecimal value,      // 因子值
    long timestamp,        // 计算时间戳
    FactorQuality quality  // WARMUP, READY, STALE
) {}
```

### FactorCalculator（因子计算器）

```java
public interface FactorCalculator {
    String name();
    Factor calculate(Instrument instrument, Timeframe timeframe);
}
```

### FactorRegistry（因子注册表）

自动发现所有 `FactorCalculator` Bean，提供统一查询接口。

### BarCache（Bar 缓存）

按 Instrument + Timeframe 缓存最近的 BarEvent，供技术指标因子使用。

## 已实现的因子

### 技术指标因子（使用 TA4J）

| 因子 | 名称 | 说明 |
|------|------|------|
| SMA_20 | 简单移动平均线 | 20 周期收盘价 SMA |
| EMA_20 | 指数移动平均线 | 20 周期收盘价 EMA |
| MACD_HIST | MACD 柱状图 | 12/26 周期 MACD |
| RSI_14 | 相对强弱指标 | 14 周期 RSI |
| BB_PCT_B | 布林带 %B | 20 周期 2 倍标准差 |

### 衍生品因子

| 因子 | 名称 | 说明 |
|------|------|------|
| FUNDING_RATE_CHANGE | 资金费率变化率 | 最近两次资金费率差值 |
| OI_CHANGE_PCT | 持仓量变化率 | 持仓量百分比变化 |
| LIQUIDATION_DENSITY | 爆仓密度 | 5 分钟窗口内爆仓总金额 |

## TA4J 集成

### 版本

- TA4J 0.17（兼容 Java 17）
- 使用 `DecimalNum` 精度（TA4J 0.17 默认）

### 转换工具

`Ta4jBarSeriesConverter` 将内部 `BarEvent` 列表转换为 TA4J `BarSeries`：
- 自动处理时间戳 → `ZonedDateTime`
- 自动处理 `BigDecimal` → `DecimalNum`
- 保留原始时间戳用于结果映射

### 使用示例

```java
@Component
public class SmaFactor implements FactorCalculator {
    private final BarCache barCache;
    private final int period = 20;

    public Factor calculate(Instrument instrument, Timeframe timeframe) {
        List<BarEvent> bars = barCache.getBars(instrument, timeframe, period + 10);
        if (bars.size() < period) return Factor.warmup(name());

        BarSeries series = Ta4jBarSeriesConverter.toBarSeries(bars, "BTCUSDT_1m");
        SMAIndicator sma = new SMAIndicator(new ClosePriceIndicator(series), period);
        BigDecimal value = BigDecimal.valueOf(sma.getValue(series.getEndIndex()).doubleValue());
        return Factor.of(name(), value, bars.get(bars.size()-1).metadata().exchangeTimestamp());
    }
}
```

## 添加新因子

1. 实现 `FactorCalculator` 接口
2. 注册为 `@Component`（自动被 FactorRegistry 发现）
3. 在 `name()` 返回唯一名称
4. 在 `calculate()` 中实现计算逻辑
5. 添加单元测试

## 不能做的事

- 不支持因子组合（如 MACD + RSI 综合评分）— 需要策略层处理
- 不支持因子持久化（第一阶段纯内存）
- 不支持历史因子查询（只能查当前值）
- 不支持因子依赖链（因子之间不能互相依赖）
