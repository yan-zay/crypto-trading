# 风控引擎

所有非 HOLD 订单在账户变更前通过 `RiskEngine`。规则按 Spring 注入顺序短路执行，规则异常按拒绝处理。

## 规则契约

```java
RiskCheckResult check(Order order, TradingAccount account);
void onOrderFilled(Order order);
RiskRule newSession();
```

`check` 必须无副作用；只有真实成交后才能在 `onOrderFilled` 记账。有状态规则必须通过 `newSession` 返回干净实例，避免模拟账户和回测相互污染。

## 当前规则

| 规则 | 作用 |
|---|---|
| `MaxLossPerTradeRule` | 单笔风险上限 |
| `MaxDailyLossRule` | 按订单事件时间计算当日损失上限 |
| `MaxPositionSizeRule` | 单订单/持仓大小限制 |
| `PerSymbolExposureRule` | 单 instrument 暴露限制 |
| `TotalExposureRule` | 账户总暴露限制 |
| `CooldownRule` | 连续亏损后的事件时间冷却 |
| `StrategyBudgetRule` | 按真实策略 ID 的资金预算，成交后记账 |

`reduceOnly` 平仓应优先降低风险，因此暴露类规则不能阻止它。`KillSwitch` 支持 HALT 和 CLOSE_ONLY，`DrawdownGuard` 提供回撤保护。

## 当前限制

- 保证金阶梯、动态杠杆、资金费率、借贷、组合保证金和交易所风险参数仍不完整。
- 规则优先级和配置 schema 尚未形成统一 rule catalog。
- 缺少相关性/因子暴露、venue/账户/策略多层限额和风险预算分配。
- 缺少独立风险服务、双人审批、限额变更告警和灾备 Kill Switch。
