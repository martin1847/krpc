# OSS-001 Wave 2 IMPL (omp) — agent 资产三件套

Branch: `feat/agent-assets` off `origin/dev` @ `2ff124b`. Executor: omp. Scope:
docs/文本资产，零代码/零发布面变更。依据 STRAT-001 R2 #1 / ADR-0004 / roadmap AGENT-001。

## 交付物（拟各自独立 commit）

1. **官方 krpc agent skill** — `skills/krpc/SKILL.md`。thin-shim（SoT=SPEC.md）：
   frontmatter（name `krpc` + 触发词）；五条速查（SPEC §1–§6，每条一行 + 节号指针）；
   agent 调用路径 curl 模板（P0 discover→invoke，端口/键格式/错误语义/凭据不绕过/agentTool
   未建）；native 指针（SPEC §13 + krpc-native-build skill）。参照 workspace
   `krpc-native-build` skill 的 thin-shim 先例。
2. **`llms.txt`** — repo 根 + `docs-site/static/llms.txt`（双落点，内容一致）。llmstxt.org
   格式（H1 + 摘要 + 分节链接）。仓内文档用 `raw.githubusercontent.com/martin1847/krpc/dev/…`；
   docs-site 用终态 `https://martin1847.github.io/krpc/`。
3. **Agent 入口文档** — `docs/agent-guide.md`。端点表（GET /agent/discover、POST
   /agent/invoke，8080 vs 50051）、暴露模型诚实版、请求/响应形状、真实 discover→invoke
   实录、"未建"清单。docs-site 经 `reference.md` 挂链。

指针（每处一行，diff 里唯二的 tracked 改动）：
- `README.md`：intro 后一行 "AI agent? install the krpc skill + agent guide"。
- `docs-site/docs/reference.md`：Handbook 节加 Agent Guide + skill 链接（= docs-site 挂链）。

## 验证（真跑）

- **curl 实录真跑**（agent-guide 核心验收）。起 quickstart fast-jar，实测：
  - `GET /agent/discover` → 返回 web-only `ApiMeta`（Hello 服务、HelloRequest.name
    `@NotBlank`、HelloReply、`@Doc`、`web:true`）。真实 JSON 已贴入 agent-guide。
  - `POST /agent/invoke {"service":"Hello","method":"hello","input":{"name":"krpc"}}`
    → `{"code":0,"data":{"message":"Hello, krpc!","timestamp":1783015301792}}`。
  - unknown/hidden → `{"code":5,"message":"Nope/x not found"}`。
- **llms.txt 链接核验**：12 条链接全过（仓内 8 条本地文件存在；docs-site URL 形态合法）。
- **五条速查对照 SPEC**：逐条比对 SPEC §1–§6 原文（§1 L17-40 / §2 L49-73 / §3 L77-110 /
  §4 L114-135 / §6 L160-178），措辞忠实、节号指针正确、无转述失真。底层代码证据抽验：
  `RefUtils.java:117-120`（方法过滤）、`:124-150`（HIDDEN_SERVICE `-` 前缀）、
  `RpcResult.java:21-24`（百/千分桶、无负码、禁异常传业务错）——与 SPEC 引用一致。
- **`gradle build -x test` 绿**（JDK 21 baseline，Gradle 9.6.0 @ /opt/gradle）。
- **diff 形态**：tracked = README +2 / reference.md +4（仅指针）；untracked = `llms.txt`、
  `docs-site/static/llms.txt`、`docs/agent-guide.md`、`skills/krpc/SKILL.md`。**零 Java/gradle
  逻辑变更**，发布模块集不动。

## 未验证 / 假设

- **未 push、未开 PR、未 commit**（按 guardrail；等 reviewer codex r1+r2 后再定 commit 切分）。
- curl 实录在 **JVM 模式 + `quarkus.arc.remove-unused-beans=none`** 下取得（见下方 FINDING）。
  未在 native 模式验证（P0 已知 native reflection-config 未加）。
- quarkusDev（`gradle :examples:quickstart:run`）在本机因 dev-mode 模型解析
  `com.google.inject:guice:5.1.0` 的 `classes` classifier 变体失败（`mavenLocal()` 优先、
  该 classifier 变体不在本地库）——与本次交付无关，绕过方式：`quarkusBuild` 出 fast-jar 后
  `java -jar` 直跑。用户环境若有独立 dev-mode 通路，实录命令可切回 quarkusDev。

## FINDING（新，需上游关注）— P0 agent 端点在默认 Quarkus 应用中不暴露

**现象**：quickstart fast-jar 默认启动打出 `HttpHandlerExpose: Skip HTTP Server , no
Handlers found.`，8080 不监听，`/agent/discover`、`/agent/invoke` 连接被拒（curl exit 7）。
gRPC 网关 50051 正常。

**根因**：`AgentDiscoverHandler`（`GetHandler`）/`AgentInvokeHandler`（`PostHandler`）是
`@ApplicationScoped` bean，但**无人 `@Inject` 它们**——`HttpHandlerExpose.initHandler()`
只在运行时用 `beanManager.getBeans(Object.class, Any)` 反射发现。Quarkus Arc 默认
`remove-unused-beans=all` 判定二者"未使用"并移除，故运行时扫不到，HTTP server 直接 skip。

**证据**：以 `-Dquarkus.arc.remove-unused-beans=none` 重建同一 fast-jar，启动即打出
`GET [/agent/discover]` / `POST [/agent/invoke]` / `HTTP Server 2 endpoints on 8080`，
三条 curl 全部按预期返回（见上）。开/关该 flag 是唯一变量。

**为何测试没抓到**：`AgentInvokeHandlerTest` / `WebMethodRegistryTest` 用 `new
AgentInvokeHandler()` + 手工 `registry.init(...)` 直接构造，从不启动 Quarkus 容器，故
Arc bean 移除路径完全不被覆盖。

**影响**：roadmap AGENT-001 标 "P0 (DONE, on dev)"，已知 caveat 仅列 "native
reflection-config 未加"。此 **Arc 移除 caveat 是新发现，任何默认配置的 Quarkus 消费者
（含 quickstart 本身）P0 端点开箱不可达**。

**处置**：本 wave 为 docs-only、零代码变更，**不在此修**。agent-guide 已以显式"已知 P0
限制 + 复现 flag"诚实登记，不谎称开箱可用。修复属独立代码变更（需批准），候选：
(a) 让 `rpc-server-quarkus` 成为带 deployment 的 Quarkus 扩展，用
`AdditionalBeanBuildItem(...).setUnremovable()` / `UnremovableBeanBuildItem` 保留
`GetHandler`/`PostHandler` 实现；(b) handler 上加 `@io.quarkus.arc.Unremovable`；
(c) `HttpHandlerExpose` 显式 `@Inject Instance<GetHandler>` / `Instance<PostHandler<?>>`
建立强引用。建议开 roadmap/issue 承接。

## 措辞纪律自检（disclosure 口径）

- 全套文本无 "complete" 声称（skill/agent-guide/llms.txt 均未用）。
- `agentTool` 一律标 **accepted-not-built / P1 design**（skill + agent-guide 各一处显式）。
- @UnsafeWeb 暴露模型写诚实版：**agent 面 = web 面过滤视图**；`agentTool` 严格子集为未建
  P1，引 ADR-0004。
- 凭据不被绕过、auth/限流为 gateway 职责——按 ADR-0004 原文。

## SoT / 边界

- 触及 SoT：ADR-0004（引用未改）、roadmap AGENT-001（引用未改）、SPEC §1–§6/§13（引用未改）。
- 无 FOR/NOT FOR 边界变更；无 ADR/roadmap 状态需改（除上方 FINDING 建议新开条目承接 P0 修复）。
