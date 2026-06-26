# ADR-0002: TA4J 技术指标库集成

## 状态

已接受

## 背景

需要计算标准技术指标（SMA、EMA、MACD、RSI、Bollinger Bands 等），候选方案：
1. 自行实现
2. 使用 TA4J
3. 使用其他库（如 XChange）

## 决策

选择 **TA4J 0.17**。

## 原因

### 为什么不用自行实现

- 标准指标有成熟的数学公式，自行实现容易出错
- TA4J 已经过广泛验证
- 维护成本高

### 为什么选择 TA4J

1. **纯 Java**：无 JNI 依赖，部署简单
2. **Java 17 兼容**：TA4J 0.17 支持 Java 11+
3. **指标丰富**：SMA、EMA、MACD、RSI、Bollinger Bands、ATR、ADX 等
4. **活跃维护**：GitHub 持续更新
5. **轻量级**：仅核心库，无额外依赖

### 为什么不选择其他库

- **XChange**：主要是交易所 API 客户端，技术指标不是重点
- **Apache Commons Math**：通用数学库，没有专门的金融指标

## 版本选择

- **TA4J 0.17**（最新稳定版）
- 使用 `DecimalNum` 精度（TA4J 0.17 默认）
- API 注意事项：
  - `BarSeries.addBar(Duration, ZonedDateTime, Num...)` 而非 `barBuilder()`
  - `ZonedDateTime` 而非 `Instant`
  - `DecimalNum.valueOf()` 而非 `DoubleNum.valueOf()`

## 影响

### 正面

- 标准指标计算开箱即用
- 社区验证的算法实现
- 可扩展（自定义指标）

### 负面

- TA4J 0.17 API 与旧版本有 breaking changes
- `DecimalNum` 精度可能比 `BigDecimal` 低（对于技术指标足够）
- 需要学习 TA4J 的 BarSeries/Indicator 模型

## 替代方案

如果未来需要更高精度或更多指标：
1. 自行实现特定指标
2. 使用 Kotlin/Python 桥接 TA-Lib
3. 升级到 TA4J 更新版本
