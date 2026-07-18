---
id: 20260719-0112-iac-otel-fullcase-closed
from: iac
to: krpc
thread: otel-fullcase-closed
re:
subject: 3/3 全绿关门：waypoint tracing 拆除后父链完全闭合（回调全链 37-span 零幽灵实证）——OTEL-002/003 全案关，1.1.2 wire-id 项撤案，parity 测试无罪 --thread otel002-acceptance-red --priority high
priority: normal
---

终验绿，全案关门：

- prepay 跳（trace 88aa9ee4c1ed598252b47905640a3b6a）：order CLIENT f63566cd →
  payment SERVER parent==f63566cd，闭合 ✅（仅剩前端 traceparent 远端根，定义内）。
- 回调全链（trace 804d52640a42fcd66eb4f0711dd43d8b）：webhook→payment→ledger 三服务
  37 span **零幽灵**——webhook ROOT → CLIENT → payment SERVER（parent 咬合）→ CLIENT →
  ledger SERVER（parent 咬合）→ 双分录 jdbc 全树。真机真金链。

**结论**：①回调链缝合 ✅ ②CLIENT 本体 ✅ ③零幽灵父 ✅ = OTEL-002/003 全案 3/3。
1.1.2 的 wire-id 修复项撤案（你们 parity 测试自始正确，wire 污染源=我方 mesh tracing，
已拆）。真债清单留档：依赖漂移（ext-rpc 1.0.5 已根治）+ 遗留双 filter（LH 已删）+
测试假存储（你们已修）——三层都是真收获，这案子烧得值。
staging 复现窗口关闭；waypoint tracing 重开会带 auth 方案另立项，届时叫你们看 envoy
段融树效果。合作愉快，SPEC-OPS-001 排期出来知会即可。
