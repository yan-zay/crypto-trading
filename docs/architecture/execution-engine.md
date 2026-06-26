# 执行引擎

> 本文档描述执行引擎的设计。

## 设计理念

执行引擎是信号 → 订单 → 成交的桥梁，必须经过风控检查。

## 流程

```
SignalEvent
    ↓
PositionSizer.calculateSize()  → 计算仓位
    ↓
Order.create()                 → 创建订单意图
    ↓
RiskEngine.checkAll()          → 风控检查
    ↓
SlippageModel.applySlippage()  → 滑点模拟
    ↓
VirtualAccount.openPosition()  → 执行
    ↓
Order.filled() / rejected()    → 结果
```

## 核心组件

### ExecutionEngine

```java
Order execute(SignalEvent signal, VirtualAccount account, BigDecimal currentPrice, long timestamp);
```

### SlippageModel

```java
BigDecimal applySlippage(BigDecimal price, OrderSide side, OrderType type);
```

### FixedSlippageModel

固定基点滑点（回测用）：
- 买入：价格上滑
- 卖出：价格下滑
- 限价单：不应用滑点

## 订单模型

```java
record Order(orderId, instrument, side, type, quantity, price, status, rejectReason, createdAt, filledAt)
```

## 不能做的事

- 不支持真实交易所 API 调用
- 不支持限价单
- 不支持部分成交
- 不支持订单取消
