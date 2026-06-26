# 可观测性设计

> 本文档描述系统可观测性的设计。

## 当前实现

### SystemMetrics

- 事件吞吐量（BarEvents/min）
- 信号数量
- 定时输出到日志（每分钟）

### Spring Boot Actuator

- `/actuator/health` — 健康检查
- `/actuator/info` — 应用信息
- `/actuator/metrics` — JVM 指标

## 后续扩展

- Micrometer + Prometheus 指标导出
- Grafana Dashboard
- 结构化日志（JSON 格式）
- 告警（连接断开、策略异常）
