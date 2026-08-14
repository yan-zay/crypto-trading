# 市场数据连接器

## 1. 目标与范围

所有外部行情先转换为内部 `MarketEvent`，策略、因子、回测和持久化不得解析交易所原始 JSON。一个 K 线序列由以下完整身份唯一标识：

```text
exchange + marketType + normalizedSymbol + timeframe
```

当前产品范围固定为：

- 平台：`BINANCE`、`COINGLASS`、`OKX`。
- 市场：`SPOT`、`PERPETUAL`。
- 标的：`BTCUSDT`、`ETHUSDT`。
- 当前明确不接入其他平台；`Exchange` 也不保留未实现的平台占位。

`MarketUniverseProperties` 是连接器、历史回填、覆盖率和回测共同使用的白名单。平台自己的订阅配置不能绕过该白名单。

## 2. 数据流与模式

```text
WS push or REST polling/history
  -> source connector/provider
  -> source normalizer
  -> BarEvent / LiquidationEvent
  -> MarketEventBus
  -> BarCache / StrategyEngine / persistence / observability
```

这里采用 Adapter + Registry：

- `MarketDataConnector` 统一实时连接生命周期和订阅行为。
- `ExchangeHistoricalDataProvider` 统一历史 K 线读取。
- `HistoricalDataProviderRegistry` 按交易所选择 provider，避免 `if/else` 平台分支散落在回填服务。
- 每个平台保留独立 normalizer，外部协议变化不会污染领域模型。

## 3. 已接入能力

| 平台 | 现货 K 线 | 永续 K 线 | 其他实时数据 | 历史 K 线 | 默认状态 |
|---|---|---|---|---|---|
| Binance | WebSocket | USD-M WebSocket | 无 | Spot REST + USD-M REST | 开启 |
| Coinglass | REST 增量轮询 | REST 增量轮询 | `liquidation_orders` WebSocket | Spot/Futures Price History REST | 有 API key 时运行 |
| OKX | Business WebSocket | Business WebSocket | 无 | `history-candles` REST | 开启 |

### 3.1 Binance

`OkHttpBinanceWebSocketClient` 管理两个独立 session：

- 现货：`wss://stream.binance.com:9443/stream`。
- USD-M 永续：`wss://fstream.binance.com/stream`。
- stream 名称：`{symbol}@kline_{interval}`。

两个 session 各自连接、重连、恢复订阅和统计健康状态，避免现货连接失败拖垮永续连接。客户端兼容 combined stream 的 `{stream,data}` 包装和原始 payload。

`BinanceHistoricalDataProvider` 根据市场选择：

- 现货 `GET /api/v3/klines`。
- USD-M 永续 `GET /fapi/v1/klines`。

### 3.2 Coinglass

Coinglass 的公共 WebSocket 路径用于聚合爆仓，不提供与本系统需求等价的 candle channel。因此 K 线没有伪装成 WebSocket，而是采用两个明确的路径：

- 历史与回填：`CoinglassHistoricalDataProvider`。
- 最新已完成 K 线：`CoinglassKlinePollingConnector` 定时调用历史接口，只发布 watermark 之后的数据。

REST 路径：

- 现货 `GET /api/spot/price/history`。
- 合约 `GET /api/futures/price/history`。
- 鉴权 header：`CG-API-KEY`。

`priceExchange` 默认是 Binance。也就是说 `Exchange.COINGLASS` 表示数据供应来源，不表示 Coinglass 是成交场所；报告必须同时保留该来源语义。Coinglass 返回 `volume_usd`，标准化后写入 quote volume，base volume 为 `0`，不能把 USD 成交额伪装成基础币数量。

没有 `COINGLASS_API_KEY` 时，K 线轮询保持未连接并报告明确健康错误；显式历史回填会拒绝执行。爆仓 WebSocket 同样依赖 API key。

### 3.3 OKX

`OkHttpOkxWebSocketClient` 使用：

```text
wss://ws.okx.com:8443/ws/v5/business
```

默认订阅 `BTC-USDT`、`ETH-USDT`、`BTC-USDT-SWAP`、`ETH-USDT-SWAP`。`OkxHistoricalDataProvider` 调用 `GET /api/v5/market/history-candles` 并使用倒序游标分页。

永续 candle 数组中的 `vol` 是合约张数，内部 base volume 使用 OKX 提供的 `volCcy`；现货 base volume 使用 `vol`。quote volume 使用 `volCcyQuote`。这一区分有测试保护。

## 4. 完成 K 线与时间边界

- 只有 finalized/closed K 线进入因子历史、回测和实时持久化。
- forming K 线与 finalized K 线在缓存中物理隔离。
- 覆盖率、回填和轮询的结束时间都是最近一根已完成 K 线，不包含当前形成中的 bucket。
- 自然键是 `(exchange, market_type, symbol, timeframe, open_time)`。
- 重连重复推送通过自然键 upsert 和缓存幂等更新消除。

## 5. 连接生命周期

- 原子连接状态防止重复握手。
- 网络失败按有上限的指数退避重连。
- 重连后重放当前订阅集合。
- 手动关闭取消 ping 与待执行重连。
- Binance 使用连接代次和 stale socket fencing，迟到的旧 socket 回调不能覆盖新连接。
- `ConnectorHealth` 暴露连接状态、最后消息、累计消息、重连次数和最后错误。

Coinglass K 线轮询是 scheduled connector，不存在 WebSocket 重连；其失败记录到健康状态，并在下一轮继续尝试。

## 6. 历史回填

`AutoBackfillService`：

1. 校验中央 market universe。
2. 计算完整市场身份的覆盖率和缺口。
3. 低于 95% 时逐 gap 调用 registry 中的平台 provider。
4. 只持久化返回范围内的已完成 K 线。
5. 使用自然键 upsert，重复回填不增加重复行。

管理 API 必须显式提供 `exchange`、`marketType`、`symbol`、`timeframe` 和天数。支持三个平台的两类市场以及 BTC/ETH。

## 7. 数据质量与血缘

当前已有：

- 缺口与覆盖率检查。
- OHLC 合法性、时间顺序、重复数据和延迟监控。
- 完整市场身份的数据版本摘要。
- Coinglass 原始爆仓消息辅助追溯。

当前没有：

- Binance/OKX 全量 raw payload 归档。
- raw 到 canonical 的可靠逐事件外键。
- durable queue/outbox、DLQ 和 offset/checkpoint。
- 对象存储、Parquet、修订版本和跨数据源冲突仲裁。

因此数据库 K 线适合当前研究和模拟盘，不应声称已经是可审计、可完全重放的数据湖。

## 8. 失败语义与边界

- 不支持的交易所、市场、币种、周期或 OKX 到期交割合约必须拒绝，不能猜测映射。
- Coinglass 无 key 时拒绝历史回填，不降级成其他来源。
- 历史 provider 的 HTTP、网络和协议错误抛出 `HistoricalDataAccessException`；Admin 返回 502，不再把上游失败伪装成“成功回填 0 根”。
- 实时外部协议错误不发布事件，并写入 connector health/log；Coinglass K 线轮询在下一周期重试。
- 管理端覆盖率小于阈值时不得把“请求已发出”当作“数据完整”。
- 当前只接入 K 线和 Coinglass 爆仓；订单簿、逐笔成交、mark/index、资金费率、OI 和完整多空比仍未接入。

## 9. 扩展要求

未来扩展数据类型时必须：

1. 先更新 market universe 和本文档，说明来源、单位和时间语义。
2. 复用 `Instrument`、`MarketEvent`、connector/provider 接口。
3. 明确 symbol、market type、时区、周期、合约乘数和 volume 单位。
4. 增加真实 payload corpus、分页、限频、错误响应、重连和跨市场隔离测试。
5. 验证 raw/canonical 血缘、数据修订和回放策略。
