# 回测引擎

## 1. 目标

回测复用生产领域中的 `MarketEvent`、`Strategy`、因子、风控、执行、账户和费用模型，但每次运行创建隔离的事件总线、缓存、策略实例、账户和风控 session。隔离可以防止两个实验之间共享持仓、风控预算或策略状态。

## 2. 时间模型

- `dataStartTime`：预热数据开始。
- `startTime`：允许记录信号和下单的时间。
- `endTime`：回放结束。
- forming bar 永不进入因子历史。
- bar N 收盘生成的信号，最早在 bar N+1 开盘成交。
- 预热 bar 更新因子和策略状态，但预热信号不创建订单。
- 所有因子通过 as-of bar slice 计算，不能读取运行结束时的最终缓存。

这套约束用于防止未来函数和同 bar close 成交造成的系统性高估。

## 3. 数据范围

Admin 回测只允许中央 market universe：

- Binance、Coinglass、OKX。
- SPOT、PERPETUAL。
- BTCUSDT、ETHUSDT。

执行前可自动检查覆盖率并通过对应历史 provider 回填。结束时间对齐最近一根完成 K 线。

## 4. 策略入口

### 4.1 预设策略

`POST /api/admin/backtests/run` 根据 `StrategyManager` 中的策略模板创建全新实例。没有无参构造器或不能隔离实例的策略会被拒绝，不复用实时运行中的 bean 状态。

### 4.2 单因子与多因子

`POST /api/admin/backtests/factor-run` 接收 `FactorStrategySpec`。规则支持：

- ALL、ANY、WEIGHTED 组合。
- `<`、`<=`、`>`、`>=`、向上穿越、向下穿越。
- 与常量、当前 close 或另一个因子比较。
- 现货 LONG_ONLY；永续 LONG_ONLY 或 LONG_SHORT。

只有实现 `BarHistoryFactorCalculator` 的因子可用于当前历史回测。依赖 funding、OI、爆仓等非 K 线事件的因子会被明确拒绝，避免悄悄读取实时内存造成历史污染。完整配置见 `multi-factor-backtesting.md`。

## 5. 账户、风控与成本

- SPOT 使用 `VirtualAccount`，无库存 SELL 不能开裸空。
- PERPETUAL 使用 `FuturesAccount`，支持研究级多空、保证金与 K 线触价强平。
- 每次运行使用新的 `RiskEngine` session。
- `FeeModel` 计算 maker/taker 手续费。
- `FillModel`、`SlippageModel` 和 execution cost/capacity 模型控制 next-bar fill、spread、参与率、平方根冲击和部分成交。
- 结束时按最后有效价格平仓。

研究级合约账户不等价于交易所风险引擎。当前没有历史 margin tier、真实 mark price、funding 或逐笔盘口冲击。

## 6. 报告

报告包含：

- 初始/最终权益、总收益和年化收益。
- 最大回撤、Sharpe、Sortino、Calmar。
- 胜率、平均盈利/亏损、Profit Factor。
- 交易数、信号数、连胜/连亏、平均持仓时间。
- 总手续费、月度收益。
- mark-to-market 权益曲线。
- 每个信号及其 point-in-time 因子快照。
- 每笔交易、净 PnL 与费用。
- 策略配置 JSON 和回测假设 JSON。
- bootstrap 均值区间、均值为正概率、概率 Sharpe、最小记录长度、证据等级和警告。
- reproducibility manifest：random seed、数据版本/哈希、配置与执行假设摘要。
- execution quality：turnover、fees、容量/成本指标和模型限制。

百分比在数据库中按既有字段语义保存，Admin DTO 统一返回小数比例，例如 `0.05` 表示 `5%`。

## 7. 持久化与导出

完成监听器在一个事务中写入：

- `backtest_run`。
- `backtest_trade`。
- `backtest_equity_point`。
- `backtest_signal`。

必需持久化失败时回测 API 不返回成功。列表接口只加载摘要，详情接口按 run ID 加载大对象，避免列表查询把全部曲线与信号读入内存。

```text
GET /api/admin/backtest-results
GET /api/admin/backtest-results/{id}
GET /api/admin/backtest-results/{id}/report?format=json|csv|markdown
```

CSV 适合指标、月收益和逐笔明细分析；JSON 保留完整结构；Markdown 适合评审归档。

持久化异步任务是管理后台默认入口：

```text
POST /api/admin/backtest-jobs
GET  /api/admin/backtest-jobs
GET  /api/admin/backtest-jobs/{jobId}
POST /api/admin/backtest-jobs/{jobId}/cancel
GET  /api/admin/backtest-jobs/compare?runIds=...
GET  /api/admin/backtest-results/{id}/research-metadata
```

worker 从 MySQL claim `QUEUED` 任务，使用有界线程池执行，持续写 stage/progress/heartbeat。状态为 `QUEUED`、`RUNNING`、`COMPLETED`、`FAILED`、`CANCEL_REQUESTED`、`CANCELLED`。重启时遗留的 `RUNNING` 任务回到队列，取消在数据装载、事件回放和结果持久化边界协作检查。同步接口暂时保留兼容。

## 8. 管理后台

`Backtest Research` 页面提供：

- 三平台、双市场、BTC/ETH、周期、窗口、预热和初始资金选择。
- 覆盖率检查与显式回填。
- 因子规则编辑器和预设策略运行。
- 现货自动限制 long-only。

`Backtest Results` 页面先读取摘要，再按选择加载详情，展示权益曲线、月收益、指标、信号因子快照、交易和报告导出。

`Backtest Jobs` 页面提交可持久化任务，展示队列、进度、失败原因和取消操作；完成结果可以多选横向比较。结果详情的 `Research quality` 标签展示统计证据、可复现清单和执行模型限制，防止只看收益率做晋级判断。

## 9. 当前不能做

- 真实盘口队列和队列位置回放；当前已有部分成交、延迟、spread、冲击和容量参数模型，但没有 L2 真值。
- 历史 funding、借币利息、动态费率等级、margin tier 与 delisting 生存偏差。
- 多资产共享保证金、组合保证金、期权和跨币种抵押。
- 多因子参数本身的版本化配置编辑；当前策略配置快照已经保存，但 factor 参数仍来自运行时配置。
- 分布式 worker、租户资源配额、参数 sweep DAG 和 artifact 生命周期策略；当前是单节点有界队列。
- Git commit/container digest 与不可变原始数据 manifest 的完整封存；当前已保存 seed、配置、假设和数据版本摘要。
- Purged K-fold、embargo、PBO、deflated Sharpe、完整多重检验族校正和 Monte Carlo 稳健性门禁。

这些限制决定当前结果用于研究和模拟盘筛选，不能单独作为实盘上线依据。
