# ADR-0001: 市场事件模型选择

## 状态

已接受

## 背景

需要定义一个统一的市场数据事件模型，用于：
- 标准化来自不同交易所的市场数据
- 解耦数据源和策略引擎
- 支持未来回测/模拟/实盘复用

候选方案：
1. Abstract class 继承体系
2. Sealed interface + record
3. 普通 interface + class

## 决策

选择 **sealed interface + record** 方案。

## 原因

### 为什么不用 abstract class

- Java record 不能继承 abstract class
- 事件是纯数据载体，不需要继承行为
- abstract class 允许任意子类，无法在编译时检查完整性

### 为什么不用普通 interface

- 普通 interface 允许任意实现，无法保证类型安全
- switch 表达式无法检查 exhaustiveness
- 无法在编译时防止未授权的实现

### 为什么选择 sealed interface + record

1. **不可变性**：record 天然不可变，线程安全
2. **类型安全**：sealed 确保编译器知道所有可能的类型
3. **模式匹配**：Java 17 的 switch 表达式可检查 exhaustiveness
4. **简洁性**：record 自动生成 equals/hashCode/toString
5. **可扩展**：添加新类型只需在 permits 列表中添加

## 影响

### 正面

- 编译时类型安全
- 不可变事件，线程安全
- 简洁的代码
- 支持模式匹配

### 负面

- 需要 Java 17+
- 添加新事件类型需要修改 sealed interface 的 permits 列表
- record 不能有子类（但这是设计意图）

## 替代方案

如果未来需要支持动态事件类型（如用户自定义事件），可以：
1. 使用普通 interface（牺牲类型安全）
2. 使用 wrapper pattern（sealed 包装动态类型）
3. 使用事件总线的 topic/channel 机制（Kafka 风格）
