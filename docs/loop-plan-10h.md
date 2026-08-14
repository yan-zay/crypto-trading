# 10 小时持续 Loop 开发方案

> 生成时间：2026-06-27
> 状态：**待确认**

---

## 总体策略

混合使用三种模式：

| 模式 | 用途 | 何时用 |
|------|------|--------|
| **Workflow 并行** | 多个独立任务同时推进 | 每轮开始时 |
| **/loop 深度迭代** | 单个复杂任务反复修复直到完成 | Workflow 产出需要深度打磨时 |
| **CronCreate 定时** | 持续运行测试、监控健康状态 | 穿插在整个过程中 |

---

## 时间表（10 小时 = 600 分钟）

### 第 1 小时（0:00-1:00）：数据管线打通

**模式：Workflow 并行**

```
Workflow "数据管线":
  ├── Agent 1: Coinglass 真实数据验证
  │   - 用 COINGLASS_API_KEY 连接 WS
  │   - 验证 LiquidationEvent 从 WS → Normalizer → EventBus → StrategyEngine
  │   - 修复所有连接/解析/订阅问题
  │   - 验证日志中出现真实爆仓信号
  │
  ├── Agent 2: Binance REST 历史数据回填
  │   - 实现 BinanceHistoricalDataProvider
  │   - 调用 /fapi/v1/klines 接口批量下载
  │   - 存入 MySQL bar_event 表
  │   - 支持 BTCUSDT/ETHUSDT 1min 数据
  │
  └── Agent 3: 新增 ATR/ADX/SuperTrend 因子
      - 实现 3 个技术指标因子（使用 TA4J）
      - 每个因子配 3+ 单元测试
      - 注册到 FactorRegistry
```

**验证点：**
- `mvn test` 通过
- Coinglass 日志出现真实爆仓数据
- MySQL bar_event 表有历史数据
- FactorRegistry 显示 11+ 因子

---

### 第 2 小时（1:00-2:00）：因子扩展 + 多交易对

**模式：Workflow 并行 + CronCreate 定时测试**

```
Workflow "因子与多交易对":
  ├── Agent 1: 衍生品因子扩展
  │   - 多空比因子（LongShortRatioFactor）
  │   - 资金费率套利因子（FundingArbitrageFactor）
  │   - 持仓量加权因子（OIWeightedFactor）
  │   - 每个因子 3+ 测试
  │
  ├── Agent 2: VWAP/成交量因子
  │   - VWAP 因子
  │   - 成交量变化率因子
  │   - 累积成交量 Delta 因子
  │
  └── Agent 3: 多交易对并行支持
      - 修改 BinanceWebSocketService 订阅多交易对
      - 修改 InMemoryBarCache 按 symbol 独立缓存
      - 修改 MacdCrossStrategy 支持多交易对
      - 添加 DataPipelineTest 多交易对集成测试

CronCreate (每 10 分钟):
  "运行 mvn test，如果有失败的测试，分析原因并修复"
```

**验证点：**
- `mvn test` 通过（持续监控）
- FactorRegistry 显示 15+ 因子
- Binance WS 同时接收 BTC/ETH/SOL K 线
- 每个交易对独立产生策略信号

---

### 第 3 小时（2:00-3:00）：回测引擎深度打磨

**模式：/loop 深度迭代**

```
/loop "用 Binance 历史数据运行完整回测，目标：
1. 下载 BTCUSDT 1min 30天数据（约 43200 根 K 线）
2. 运行 MacdCrossStrategy 回测
3. 验证性能报告：总收益、最大回撤、胜率、盈亏比
4. 修复回测过程中发现的所有问题：
   - 因子计算在大数据量下的性能
   - 内存使用（43200 根 K 线的 BarCache）
   - 风控规则在回测中的行为
   - 滑点模型的影响
5. 输出回测报告到 docs/backtest-report-YYYY-MM-DD.md
6. 循环直到回测完成且报告可信"
```

**验证点：**
- 回测完成，无异常
- 性能报告包含所有指标
- 报告中的数字合理（不是 0 或极端值）

---

### 第 4 小时（3:00-4:00）：参数优化 + 策略增强

**模式：Workflow 并行**

```
Workflow "策略优化":
  ├── Agent 1: MACD 参数网格搜索
  │   - fast: [8, 10, 12, 14, 16]
  │   - slow: [20, 24, 26, 30, 34]
  │   - signal: [7, 9, 11]
  │   - 共 75 组合，每组运行回测
  │   - 输出最优 Top 5 参数组合
  │
  ├── Agent 2: 新增 RSI 策略
  │   - RsiOverboughtOversoldStrategy
  │   - RSI > 70 卖出，RSI < 30 买入
  │   - 配合回测验证
  │
  └── Agent 3: 新增 Bollinger Band 策略
      - BollingerBreakoutStrategy
      - 突破上轨买入，突破下轨卖出
      - 配合回测验证
```

**验证点：**
- 参数优化输出 Top 5 组合及对应指标
- 3 个策略都有回测结果
- `mvn test` 通过

---

### 第 5 小时（4:00-5:00）：模拟交易 + 实时验证

**模式：/loop + CronCreate**

```
/loop "启动模拟交易引擎，接入实时 Binance 数据：
1. PaperTradingEngine.start(10000)
2. 运行 MacdCrossStrategy + RsiStrategy + BollingerStrategy
3. 验证实时信号产生
4. 验证虚拟开仓/平仓
5. 修复发现的所有问题
6. 循环直到模拟交易稳定运行 10 分钟无异常"

CronCreate (每 5 分钟):
  "检查模拟交易状态，输出当前持仓、余额、信号数量到日志"
```

**验证点：**
- 模拟交易稳定运行 10+ 分钟
- 日志中出现 BUY/SELL 信号
- VirtualAccount 正确跟踪持仓和余额

---

### 第 6 小时（5:00-6:00）：风控增强 + 执行完善

**模式：Workflow 并行**

```
Workflow "风控执行":
  ├── Agent 1: 动态风控规则
  │   - 基于波动率的动态仓位调整（ATR-based position sizing）
  │   - 追踪止损（trailing stop）
  │   - 最大连续亏损保护
  │
  ├── Agent 2: 执行引擎增强
  │   - 限价单支持
  │   - 部分成交处理
  │   - 手续费模型（maker/taker）
  │
  └── Agent 3: 回测手续费/资金费率
      - 回测中加入手续费计算
      - 回测中加入资金费率计算
      - 验证对策略收益的影响
```

**验证点：**
- 新风控规则有单元测试
- 限价单有测试
- 回测报告包含手续费和资金费率

---

### 第 7 小时（6:00-7:00）：多策略组合 + 组合回测

**模式：/loop 深度迭代**

```
/loop "实现多策略组合管理器：
1. StrategyPortfolio 管理多个策略
2. 资金分配：每个策略分配固定比例资金
3. 信号冲突处理：多策略同时发信号时的优先级
4. 组合回测：MACD + RSI + Bollinger 三策略组合
5. 对比单策略 vs 组合策略的收益/风险
6. 输出对比报告
7. 循环直到组合回测结果可信"
```

**验证点：**
- 组合回测完成
- 单策略 vs 组合策略对比报告
- 资金分配正确

---

### 第 8 小时（7:00-8:00）：数据质量 + 监控

**模式：Workflow 并行**

```
Workflow "数据与监控":
  ├── Agent 1: 数据质量检查
  │   - 缺失 K 线检测
  │   - 重复数据检测
  │   - 时间戳异常检测
  │   - 数据质量报告
  │
  ├── Agent 2: 指标监控增强
  │   - 连接状态监控
  │   - 因子计算延迟监控
  │   - 策略信号延迟监控
  │   - 持久化队列深度监控
  │
  └── Agent 3: 日志结构化
      - 统一日志格式
      - 信号日志（JSON 格式）
      - 交易日志（JSON 格式）
      - 错误日志（带上下文）
```

**验证点：**
- 数据质量报告生成
- 指标端点 /actuator/metrics 包含自定义指标
- 日志格式统一

---

### 第 9 小时（8:00-9:00）：压力测试 + 边界修复

**模式：/loop 深度迭代**

```
/loop "压力测试和边界修复：
1. 大数据量回测：90天 1min 数据（129600 根 K 线）
2. 高频信号：模拟每秒 1 个 BarEvent，持续 10 分钟
3. 并发策略：5 个策略同时运行
4. 异常数据：注入畸形 JSON、null 字段、负价格
5. 网络中断：模拟 WebSocket 断线重连
6. 修复所有发现的问题
7. 循环直到所有压力测试通过"
```

**验证点：**
- 90 天回测完成无 OOM
- 高频信号无丢失
- 异常数据不崩溃
- 断线重连后数据恢复

---

### 第 10 小时（9:00-10:00）：文档 + 清理 + 最终验证

**模式：Workflow 并行**

```
Workflow "收尾":
  ├── Agent 1: 文档更新
  │   - 更新所有架构文档
  │   - 更新 CLAUDE.md
  │   - 生成 API 文档
  │   - 生成部署文档
  │
  ├── Agent 2: 代码清理
  │   - 删除所有死代码
  │   - 统一命名规范
  │   - 清理未使用的 import
  │   - 清理未使用的依赖
  │
  └── Agent 3: 最终验证
      - mvn clean test（全部通过）
      - mvn package（构建成功）
      - java -jar 启动成功
      - 回测验证（用最新代码重新跑一次）
      - 模拟交易验证（运行 5 分钟）
```

**验证点：**
- 所有测试通过
- 应用启动成功
- 文档完整
- 代码干净

---

## 执行机制

### 每轮 Workflow 的标准流程

```
1. 启动 Workflow（并行 Agent）
2. 等待完成
3. 检查 mvn test 结果
4. 如果失败 → 立即修复
5. 提交代码
6. 进入下一轮
```

### CronCreate 穿插任务

在整个 10 小时过程中持续运行：

```
CronCreate("*/10 * * * *", "运行 mvn test，报告失败数量和失败测试名称")
CronCreate("*/30 * * * *", "检查应用是否能正常启动（mvn -DskipTests package && timeout 15 java -jar）")
```

### /loop 使用时机

当 Workflow 产出需要深度打磨时切换到 /loop：
- 回测结果不可信 → /loop 修复
- 模拟交易不稳定 → /loop 修复
- 压力测试失败 → /loop 修复

---

## 预期产出

10 小时后系统应具备：

| 能力 | 状态 |
|------|------|
| Binance 实时 K 线 | ✅ 多交易对 |
| Coinglass 实时爆仓 | ✅ 已验证 |
| 历史数据回填 | ✅ REST API |
| 技术指标因子 | ✅ 15+ 个 |
| 衍生品因子 | ✅ 5+ 个 |
| 策略数量 | ✅ 3+ 个（MACD, RSI, BB） |
| 参数优化 | ✅ 网格搜索 |
| 回测引擎 | ✅ 30-90 天数据 |
| 模拟交易 | ✅ 24h 验证 |
| 风控规则 | ✅ 动态仓位 + 追踪止损 |
| 多策略组合 | ✅ 资金分配 |
| 数据质量 | ✅ 检测 + 报告 |
| 监控指标 | ✅ 自定义指标 |
| 测试数量 | ✅ 200+ |
| 文档 | ✅ 完整 |

---

## 风险控制

| 风险 | 应对 |
|------|------|
| 某个 Agent 卡住超过 20 分钟 | 跳过该任务，记录问题，继续下一轮 |
| 测试大面积失败 | 停止新功能开发，全力修复 |
| 内存溢出 | 减少数据量，优化缓存策略 |
| API 限流 | 加入重试和退避逻辑 |
| 代码冲突 | 每轮提交前先 pull |

---

**等待确认后开始执行。**
