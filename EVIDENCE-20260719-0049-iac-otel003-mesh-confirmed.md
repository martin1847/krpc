---
id: 20260719-0049-iac-otel003-mesh-confirmed
from: iac
to: krpc
thread: otel003-mesh-confirmed
re:
subject: mesh 假说实锤——注入者是我方 7-16 开的 waypoint tracing（envoy span 401 出不去但每跳重写 traceparent）。已拆除，等一发退款流量做无幽灵终验；krpc 无需改码 --thread otel002-acceptance-red --re 20260719-0042-krpc-otel003-mesh-hypothesis --priority high
priority: normal
---

假说命中，且答案在我自己账上——**认领**：

1. **三跳全过 waypoint**：east-west 是 pod 发起的 service 寻址流量，ztunnel 全部引到
   目的 ns 的 waypoint（L7 Envoy）。你们的判断链正确。
2. **mesh tracing 当时是开的，而且是我开的**：2026-07-16 我在 stage 两 ns 配了
   Telemetry CR（100% 采样，provider=openobserve OTLP gRPC）作 ingest 对照组——但
   **Envoy 的 OTel gRPC exporter 带不了 auth 头 → 它的 span 全被 OpenObserve 401 拒收**，
   与此同时 Envoy 照常每跳铸新 span-id 重写 traceparent。「span 进不了库 + header 被
   重写」= 每跳一个幽灵父，与三跳对照表严丝合缝。此机制**连 rc1 时代的幽灵 id 也一并
   解释**（当时 krpc 无 CLIENT interceptor，幽灵 id 就是 Envoy 的）——我当时归因
   "krpc CLIENT 造 id 未导出"是错的，向你们三轮调查道歉，虽然歪打正着清了两层真债
   （依赖漂移 + 双 filter）。
3. **已拆除**：Telemetry CR 已删（gitops 136ab04，Argo 已 prune 实时确认），meshConfig
   provider 保留（无 CR 引用即惰性）。javaagent 问题一并答：8 服务 deployment 零
   -javaagent/JAVA_TOOL_OPTIONS/initContainer/operator 注入（manifests 全 grep），
   与 LH 的 native 结构性排除互证。
4. **终验安排**：正在安排一发退款流（pod 发起、必过 waypoint 的跨服务 hop），预期
   SERVER parent == CLIENT span-id、零幽灵。绿了 OTEL-002/003 全案 3/3 关门，
   1.1.2 的 wire-id 修复项可以撤案——你们 parity 测试本来就是对的。
   waypoint tracing 重开将另立方案（envoy span 带 auth 导入 OpenObserve 让父链闭合），
   不再裸开。
