# 单因子与多因子回测

## 1. 为什么这样设计

系统已有 `FactorCalculator` 和 `Strategy` 两套扩展点。多因子回测没有再建立一套表达式引擎，而是用不可变的 `FactorStrategySpec` 描述规则，再由 `CompositeFactorStrategy` 实现标准 `Strategy`。这样可以继续复用 point-in-time context、执行、风控、账户、报告和持久化。

优势：

- 单因子是只有一条 rule 的多因子特例。
- 规则 JSON 可序列化、审计和写入回测快照。
- UI、API 和回测引擎共享同一模型。
- 新因子仍通过 `FactorRegistry` 注册，无需修改组合策略。
- 非历史安全因子在运行前被拒绝，不做隐式降级。

## 2. 领域模型

```text
FactorStrategySpec
  name
  positionMode: LONG_ONLY | LONG_SHORT
  longEntry: FactorRuleGroup
  longExit: FactorRuleGroup
  shortEntry: FactorRuleGroup (LONG_SHORT required)
  shortExit: FactorRuleGroup (LONG_SHORT required)

FactorRuleGroup
  mode: ALL | ANY | WEIGHTED
  minimumMatchRatio: 0..1
  rules: FactorRule[]

FactorRule
  factorName
  operator
  target: CONSTANT | PRICE | FACTOR
  threshold | targetFactorName
  weight > 0
```

### 2.1 匹配模式

- `ALL`：全部规则为 true。
- `ANY`：至少一条规则为 true。
- `WEIGHTED`：命中规则权重 / 总权重不低于 `minimumMatchRatio`。

### 2.2 操作符

- `LT`、`LTE`、`GT`、`GTE` 是当前时点比较。
- `CROSS_ABOVE` 要求上一时点左值不大于右值，当前时点左值大于右值。
- `CROSS_BELOW` 语义相反。

交叉比较按完整 `MarketSeriesKey` 保存上一值，不会让 BTC/ETH、现货/永续或不同平台互相污染。

## 3. 请求示例

```json
{
  "exchange": "BINANCE",
  "marketType": "PERPETUAL",
  "symbol": "BTCUSDT",
  "timeframe": "1h",
  "days": 180,
  "warmupBars": 200,
  "initialBalance": 10000,
  "autoBackfill": true,
  "strategy": {
    "name": "RSI and trend filter",
    "positionMode": "LONG_ONLY",
    "longEntry": {
      "mode": "ALL",
      "minimumMatchRatio": 1,
      "rules": [
        {
          "factorName": "RSI",
          "operator": "LTE",
          "target": "CONSTANT",
          "threshold": 30,
          "weight": 1
        },
        {
          "factorName": "MACD_HIST",
          "operator": "CROSS_ABOVE",
          "target": "CONSTANT",
          "threshold": 0,
          "weight": 1
        }
      ]
    },
    "longExit": {
      "mode": "ANY",
      "minimumMatchRatio": 1,
      "rules": [
        {
          "factorName": "RSI",
          "operator": "GTE",
          "target": "CONSTANT",
          "threshold": 70,
          "weight": 1
        }
      ]
    }
  }
}
```

能力元数据由 `GET /api/admin/backtests/capabilities` 返回，前端不得硬编码一个更宽的平台或币种集合。

## 4. Point-in-time 安全

当前可回测因子必须实现 marker `BarHistoryFactorCalculator`。它表示因子可以只从传入的 finalized bar slice 确定性计算。

不能用于当前组合回测的典型因子：

- 只存在于实时内存中的 funding 变化。
- OI 变化。
- 爆仓密度。
- 没有持久化历史序列的其他事件因子。

即使这些因子已经注册，`BacktestApplicationService` 也会在加载数据前拒绝，避免产生看似正常但不可复现的结果。

## 5. 信号与仓位

- FLAT 满足 entry 时发出 BUY 或 SELL。
- LONG 满足 long exit 时发出 SELL。
- SHORT 满足 short exit 时发出 BUY。
- SPOT 只允许 LONG_ONLY。
- 信号发生在完成 K 线，最早在下一 K 线开盘执行。

策略保存逻辑仓位状态以抑制每根 K 线重复发 entry。当前回测使用同步、单仓位、研究级成交模型；未来引入部分成交或异步拒单后，策略状态必须改为由 OMS/position feedback 驱动。

## 6. 报告与可复现性

每次运行持久化策略 spec、假设、信号快照、权益点和交易。报告可导出 JSON、CSV、Markdown。

当前还不能实现严格的跨机器复现，因为以下信息尚未完整封存：

- Git commit/build artifact checksum。
- 原始数据集版本和修订版本。
- 每个因子内部参数的独立版本。
- 风控、费用和 instrument metadata 的版本 ID。

## 7. 测试要求

修改组合回测时至少覆盖：

- ALL、ANY、WEIGHTED 真值组合。
- 常量、价格、因子比较。
- CROSS 边界和首个样本。
- BTC/ETH、平台、市场、周期状态隔离。
- SPOT 拒绝 LONG_SHORT。
- 非 bar-history 因子拒绝。
- 下一根开盘成交和 warmup 不交易。
- 配置序列化、完整持久化和三种导出。
- 管理后台提交多规则请求和摘要/详情分离加载。

## 8. 后续演进

- 版本化 factor parameter set 和 strategy spec 草稿/审批/发布。
- 异步实验队列、进度、取消、并发配额和 artifact retention。
- 多运行对比、参数网格/贝叶斯优化、walk-forward 与 purged CV。
- 事件因子历史仓库及 online/offline feature parity。
- OMS 驱动的策略仓位反馈，支持拒单、部分成交和恢复。
