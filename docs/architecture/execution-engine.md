# 执行引擎

`ExecutionEngine` 是信号、风控、账户与 OMS 之间的领域编排器。

```text
SignalEvent
  -> resolve BUY/SELL and LONG/SHORT action
  -> PositionSizer
  -> ExchangeRules align/validate
  -> Order CREATED + required OMS journal
  -> SUBMITTED
  -> RiskEngine
  -> ACKNOWLEDGED
  -> SlippageModel
  -> TradingAccount open/close
  -> FILLED/REJECTED + OMS fill/trade
```

## 关键语义

- BUY/SELL 是交易所指令方向，LONG/SHORT 是持仓方向，不能混用。
- 无仓 BUY 开多；无仓 SELL 在永续开空，在现货拒绝。
- 反向信号按实际持仓数量平仓并设置 `reduceOnly=true`。
- 同向信号当前拒绝，不支持加仓；这是一项明确限制。
- HOLD 不创建订单。
- tick size、step size、min quantity 和 min notional 由 `ExchangeRules` 对齐和校验。
- `OrderStateMachine` 管理创建、提交、确认、部分成交、成交、拒绝和撤单状态。

## 会话

实时执行使用 Spring 单例模板。每个模拟账户通过 `newAccountSession()` 获得独立有状态风控规则，同时共享全局 Kill Switch 和 OMS。每次回测通过 `withoutJournals()` 获得独立风控与 Kill Switch，并禁止写 OMS。

## 持久化失败

`OmsPersistenceService` 是执行必需 journal。成交前状态无法写入时 fail-closed，账户不变；成交后的跨系统一致性仍依赖未来 reconciliation，见 `oms-ems.md`。

## 当前限制

- 没有交易所私有 API、真实订单回报或多 venue 路由。
- 市价单采用本地立即成交；状态机支持部分成交/撤单，但主模拟路径尚不产生真实异步部分成交。
- 不支持加仓、部分减仓、反手一体单和 hedge mode 双向持仓。
- 固定滑点不能代表盘口深度、延迟和市场冲击。
