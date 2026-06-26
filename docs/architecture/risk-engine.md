# 风控引擎

> 本文档描述风控规则引擎的设计。

## 设计理念

风控是信号 → 执行链路中的必经环节。任何交易信号在执行前必须通过所有风控规则。

## 架构

```
SignalEvent → ExecutionEngine → RiskEngine.checkAll() → Order → 执行
                                    ↓
                              RiskRule 1 → RiskRule 2 → RiskRule N
```

## 核心组件

### RiskRule 接口

```java
public interface RiskRule {
    String name();
    RiskCheckResult check(Order order, VirtualAccount account);
}
```

### RiskEngine

串联所有规则，任一规则不通过则拒绝订单。

### 已实现的规则

| 规则 | 说明 | 默认值 |
|------|------|--------|
| MaxLossPerTradeRule | 单笔最大亏损限制 | 2% |
| MaxDailyLossRule | 每日最大亏损限制 | 5% |
| MaxPositionSizeRule | 最大持仓量限制 | 30% |

### PositionSizer

根据信号置信度和风控规则计算建议仓位：
- 基础仓位 = 余额 * 最大持仓占比
- 调整后 = 基础仓位 * 置信度

## 添加新规则

1. 实现 `RiskRule` 接口
2. 注册为 `@Component`（自动被 RiskEngine 发现）
3. 添加测试

## 不能做的事

- 不支持动态规则配置（运行时修改规则）
- 不支持规则优先级
- 不支持相关性限制（BTC/ETH 同向持仓）
