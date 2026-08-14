# OKX 市场数据接入

## 1. 设计目的

OKX 公共现货与永续 K 线统一转换为 `BarEvent`，下游不感知 OKX instrument/channel 格式。完整身份仍是 `exchange + marketType + symbol + timeframe`，不会与 Binance 或 Coinglass 的同名序列混用。

## 2. 组件

| 组件 | 职责 |
|---|---|
| `OkHttpOkxWebSocketClient` | Business WebSocket、订阅、ping、退避重连、健康状态 |
| `OkHttpOkxWebSocketService` | Spring 生命周期 |
| `OkxMarketDataMappings` | instrument、channel、timeframe 映射 |
| `OkxKlineNormalizer` | candle array 转标准 K 线 |
| `OkxHistoricalDataProvider` | `history-candles` 倒序分页、去重、排序 |
| `OkxProperties` | URL、开关、instrument、周期 |
| `MarketUniverseProperties` | 平台、市场与 BTC/ETH 白名单 |

## 3. 配置

OKX 默认开启，只订阅 BTC/ETH 的现货和 USDT 永续：

```yaml
crypto:
  connector:
    okx:
      enabled: ${OKX_ENABLED:true}
      websocket-url: ${OKX_WS_URL:wss://ws.okx.com:8443/ws/v5/business}
      rest-base-url: ${OKX_REST_BASE_URL:https://www.okx.com}
      instruments: ${OKX_INSTRUMENTS:BTC-USDT,ETH-USDT,BTC-USDT-SWAP,ETH-USDT-SWAP}
      timeframes: ${OKX_TIMEFRAMES:1m}
```

即使 `OKX_INSTRUMENTS` 被改成其他币种，连接器仍会经过中央 universe 校验并拒绝启动。要扩展币种必须先显式修改产品白名单、测试和文档。

启停、URL 或订阅集合变更当前要求受控重启。配置中心可保存和校验值，但不会在运行中的 WebSocket 上热切换连接。

## 4. 映射

| 内部 | OKX | 市场 |
|---|---|---|
| `BTCUSDT` | `BTC-USDT` | SPOT |
| `ETHUSDT` | `ETH-USDT` | SPOT |
| `BTCUSDT` | `BTC-USDT-SWAP` | PERPETUAL |
| `ETHUSDT` | `ETH-USDT-SWAP` | PERPETUAL |

支持周期：`1m`、`5m`、`15m`、`30m`、`1h`、`4h`、`1d`。日线映射到 UTC 边界。到期交割合约会显式拒绝。

## 5. Candle 语义

OKX array 关键字段为：

```text
[ts, open, high, low, close, vol, volCcy, volCcyQuote, confirm]
```

- `confirm=1` 表示完成。
- SPOT：base volume 使用 `vol`。
- PERPETUAL：`vol` 是合约张数，base volume 使用 `volCcy`。
- quote volume 使用 `volCcyQuote`。

合约张数不能直接进入统一 base-volume 指标，否则 BTC 与 ETH、不同合约面值之间不可比较。

## 6. 实时与历史流

```text
OKX business WebSocket
  -> OkHttpOkxWebSocketClient
  -> OkxKlineNormalizer
  -> MarketEventBus

OKX history-candles REST
  -> OkxHistoricalDataProvider
  -> AutoBackfillService
  -> bar_event upsert
```

REST 每页最多 300 根，使用 `after` 向更早数据翻页；输出按 open time 排序、去重、裁剪请求区间，并只保留完成 K 线。

## 7. 故障语义

- 重复 `connect` 不产生第二次握手。
- 手动断开取消 ping 和排队中的重连。
- 网络失败指数退避，成功后清零连续失败次数。
- 重连后重放订阅。
- subscription error 写入 `lastError`，无效 payload 不发布。
- universe 外 instrument、未知市场和到期合约直接拒绝。

## 8. 能力边界

能做：BTC/ETH 现货与 USDT 永续公共 K 线实时订阅、历史回填、覆盖率、持久化、因子和回测。

不能做：私有账户/订单流、实盘下单、订单簿、逐笔成交、mark/index、资金费率、OI、期权、到期交割合约和 instrument metadata 自动同步。
