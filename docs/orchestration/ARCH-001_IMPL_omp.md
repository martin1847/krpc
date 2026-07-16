# ARCH-001 IMPL (omp) — krpc ArchUnit architecture gate

Branch: `feat/arch-gate-001` off `origin/dev` @ `049e8ff`. Executor: omp. Worktree:
`/Users/martin/Garden/middleware/wt-arch001`. Commits stay LOCAL (push requires
martin approval).依据：umbrella `docs/NORTH_STAR.md`（NS-1/NS-3/NS-7 + agent-era
doctrine：gate 必须 fail build、咬整类、baseline 只缩不长）；ADR-0005。本文档含 r1
初版 + codex REQUEST-CHANGES 后的 fixround（B1/B2/B3 + A1/A2/A3）。

## Premises verified (do-not-trust check)

- **NORTH_STAR 存在且含 NS-1..NS-8** — 已读 umbrella `docs/NORTH_STAR.md`，齐全。✅
- **`:test-api:test` clean 上红（no-tests-discovered，Gradle 9）** — 动手前真跑复现：
  `BUILD FAILED ... did not discover any tests ... failOnNoDiscoveredTests`。✅
- **ArchUnit 最新 stable 1.x = 1.4.1**（Maven Central 查证），JDK21/Gradle9/JUnit5 实跑通过。✅

## What changed（最终态）

新增 **test-only、不发布** 模块 `arch-test`（ARCH-001 gate）：

- `arch-test/build.gradle` — JUnit5 + `com.tngtech.archunit:archunit-junit5:1.4.1`，
  `testImplementation` 六个核心模块。不 apply `upload.gradle` → 不发布（与
  `examples/quickstart` 同先例，`0c0ca37`）。`test { useJUnitPlatform() }`（根
  `subprojects.test{}` 的 `useJUnitPlatform` 是注释掉的，必须本模块显式开）。
- `arch-test/src/test/java/tech/krpc/arch/ArchitectureTest.java` — 规则集 v1，每条标
  NS-ID，全部 `FreezingArchRule.freeze(...)`。
- `arch-test/src/test/resources/archunit.properties` — freeze ratchet（全锁死）。
- `arch-test/archunit_store/`（committed 文本 store，8 rule 文件 + `stored.rules`）。
- `settings.gradle` — `include 'arch-test'`。
- `test-api/build.gradle` — rider：`test { failOnNoDiscoveredTests = false }`。
- `AGENTS.md` — common commands 加 `gradle :arch-test:test`。
- `docs/decisions/ADR-0005-architecture-gates.md`（accepted）。
- 本 findings 文档。

### 分析范围（fixround B2 关键修正）

`@AnalyzeClasses(packages="tech.krpc")` 会扫描 classpath 上**所有** `tech.krpc.*` 类，
包括传递依赖 jar。rpc-server-quarkus 依赖已发布的第三方 `tech.krpc.ext:ext-rpc`
（group `tech.krpc.ext`，仓内无源码），其 `tech.krpc.ext.runtime..`（ClientConfig /
Recorder / graal substitutions 等 10 个类）会被误纳入分析——这是 scope bug。
修正：自定义 `OnlyCoreModules implements ImportOption`，按类文件 location 只放行六个
核心模块自己的产物（class 目录或其 `build/libs` jar），排除传递 `tech.krpc.*` jar。
末尾斜杠消歧：`/rpc-server/` 不匹配 `/rpc-server-quarkus/`，`/rpc-client/` 不匹配
`/rpc-client-spring/`。与 `DoNotIncludeTests` 组合（AND）。

### 从代码推导的 package roots（未猜）

| layer | packages | module |
|---|---|---|
| api | `tech.krpc.annotation`, `tech.krpc.model` | rpc-api |
| common | `tech.krpc.{common,context,filter,internal,serial,util}` | rpc-common |
| client | `tech.krpc.client` | rpc-client |
| server | `tech.krpc.server` | rpc-server + rpc-server-quarkus（同 package） |
| http | `tech.krpc.http` | http-server |

### 规则集 v1

- **R1（structure）** `slices().matching("tech.krpc.(*)..").should().beFreeOfCycles()`，
  全局切片（合并 classpath），跨模块环也抓。
- **R2（layering, NS-1/NS-2）** 方向：api ↛ common/client/server/http；common ↛
  client/server/http；client ↮ server（4 个独立 `@ArchTest`）。**+ 完整性守卫
  `r2_all_classes_classified`（fixround B2，也冻结）**：所有 owned production 类必须落在
  已分类层根内；新出现的未分类包（如 `tech.krpc.newpkg`）直接令构建变红，逼出有意识的
  层归属，方向规则无法被「没有 `that()` 谓词匹配到的未分类包」静默绕过（anti-vacuous-green）。
- **R3（NS-3 denylist）** 全 `tech.krpc..`（无 allowlist）↛ discovery/registry/LB/
  telemetry-backend：`io.kubernetes..`,`io.fabric8..`,`com.ecwid.consul..`,
  `com.orbitz.consul..`,`com.netflix..`,`org.apache.zookeeper..`,`org.apache.curator..`,
  `io.etcd..`,`com.alibaba.nacos..`,`org.springframework.cloud..`,`io.micrometer..`,
  `io.opentelemetry.sdk..`（OTel API 放行，仅禁 SDK 后端）。
- **R4（NS-7）** 全 `tech.krpc..` ↛ `sun..`/`com.sun..`/`jdk.internal..`。

（R3/R4 fixround B2 从旧 allowlist 改为整个 `tech.krpc..`——range 上界不再是「今天的包」，
未来核心模块新增包自动纳管。）

### 刻意不加

`RpcResult` 方法契约已由 `RefUtils` 运行时强制；ArchUnit 再编码=重复、无编译期增益 → 不加。

## Freeze ratchet（fixround B1：堵住新 rule-id 后门）

committed `archunit.properties`：
```
freeze.store.default.allowStoreCreation=false
freeze.store.default.allowStoreUpdate=false
freeze.refreeze=false
```
`allowStoreUpdate` 默认 **true** 是后门：未知 rule 描述（新规则 / 改名 `.as(...)`）会静默
建映射并把当前全部违规冻成通过基线。置 false 后：新/改名规则无 store 条目 → **响亮失败**
（`StoreUpdateFailedException`），已有条目也不可改写。store 在 committed 配置下只读 →
**每一次**基线变更（加规则 / 修复后缩小）都是有意识、可评审、进 commit 的动作，绝非 CI
静默副作用。代价：放弃 ArchUnit 的自动 prune-on-fix，换取评审可见性（store diff = 审计轨迹，
CI 永不增长基线）。重生成 store 的唯一有效路径：临时把两个 write flag 都翻 true → 跑一次 →
翻回 false → 评审 store diff → 提交（`-Darchunit.*` 系统属性覆盖**无效**，ArchUnit 只读
classpath 上的 `archunit.properties`）。

## What verified（真跑，本 session）

- **`gradle :arch-test:test` 绿（frozen baseline，两个 write flag 均 false）**：8 rule 全过。
- **Ratchet md5 证明**：store 全文件合并 md5 `b260c707e8c2d4f7f1e3dad41978d1a5`，`--rerun-tasks`
  再跑后不变 → clean re-run 不新建/不增长 store。
- **B1 后门 probe（新 rule-id）**：临时加一条全新描述的冻结规则 `zzprobe_b1_backdoor`
  （0 违规），在 committed 锁死配置下跑 → **BUILD FAILED**，报文
  `StoreUpdateFailedException: Updating frozen violations is disabled`（未静默建条目、未静默通过）；
  删除 probe → 绿，store md5 仍 `b260c707…`，store 文件数 9（8 rule + index，无 probe 条目）。
- **B2 seeded self-proof（新包 + com.sun）**：在 `rpc-api/.../tech/krpc/zznew/Seed.java`
  （新包 `tech.krpc.zznew` + `com.sun.management.OperatingSystemMXBean`）种下 →
  **BUILD FAILED，恰好 2 条失败**：`r2_all_classes_classified`（完整性守卫）+
  `r4_no_jdk_internal`（R4）双双点名；其余绿。删种子 → 绿，md5 仍 `b260c707…`。种子已删，
  不入任何 commit。
- **Rider（`:test-api:test`）red-before / green-after**：before `BUILD FAILED ... did not
  discover any tests`；after（`failOnNoDiscoveredTests=false`）`> Task :test-api:test` +
  `BUILD SUCCESSFUL`。test-api 的 src/test 全是 `main()` demo（无 `@Test`），确无可跑测试；
  未删任何 test 源文件、未改测试行为。
- **Root lifecycle 证明**：`gradle test --dry-run` 图含 `:arch-test:test`；实跑
  `gradle check --rerun-tasks -x <集成重模块…>` → `> Task :arch-test:test` 实执行 +
  `BUILD SUCCESSFUL`。排除项=会连内网 MySQL 的集成模块（规避已知本地 hang）；exact 命令：
  `gradle check --rerun-tasks -x :test-server:test -x :test-server-spring:test
  -x :rpc-server-quarkus:test -x :rpc-server-spring:test -x :rpc-client-spring:test
  -x :rpc-server:test -x :test-jwks:test -x :test-api:test -x :examples:quickstart:test
  -x :examples:test`。

### Per-rule frozen violation counts（最终基线）

| rule | count |
|---|---|
| R1 slices cycles | **2**（grandfathered：slices `common`/`internal`/`util`）|
| R2 completeness guard | 0 |
| R2 api independent | 0 |
| R2 common below runtime | 0 |
| R2 client ↛ server | 0 |
| R2 server ↛ client | 0 |
| R3 infra denylist | 0 |
| R4 jdk-internal | 0 |

R1 的 2 条环是既存债务，冻结 grandfather；只允许减少（修复后按上述重生成流程缩小并提交）。

## NOT verified / 假设

- **未跑** 裸 root `gradle test` / 含 test 的全量 `gradle build` —— 会命中连接内网 MySQL
  主机的集成测试并可能 hang，goal 明确禁用为 proof 命令；改用 per-module + 排除式 root `check`。
- **未跑** native-image / Quarkus native 构建（本 gate 纯 test-only、零运行时行为变更；R4 正是
  为 native 护栏而设）。
- **未接** CI（GitHub Actions）—— goal 明列为 follow-up，非本 scope。
- 假设 `arch-test:test` 进 root `check`/`test` 生命周期靠「普通 subproject + 有 test task」自动
  成立（dry-run + 实跑双证）。

## 意外发现 & 关键决策

1. **[fixround B2] `tech.krpc.ext:ext-rpc` 传递 jar 污染分析范围**：`packages="tech.krpc"`
   吃进第三方 jar 的 `tech.krpc.ext.runtime..`。→ 加 `OnlyCoreModules` location 过滤，锁定
   六模块自有产物。这也顺带证明了完整性守卫（B2 要求）确实在工作——它第一次跑就抓到这 10 个
   未分类类。
2. **[fixround B1] `allowStoreUpdate` 语义**：`allowStoreCreation=false` 只挡「整个 store 目录
   不存在时的创建」；已存在 store 下新增 rule 条目走的是 update 路径，默认 true 会静默自冻。置
   `allowStoreUpdate=false` 才真正堵死后门（probe 已证 `StoreUpdateFailedException`）。
3. **ArchUnit 不读 `-Darchunit.*` 覆盖 freeze 配置**：只认 classpath 上 `archunit.properties`。
   重生成 store 用「临时翻两 flag → 跑 → 翻回」流程。ADR-0005 与 properties 注释已统一为这一条
   真实路径（fixround A2：移除自相矛盾的 `-D` 首选建议）。
4. **cycle violation 多行（反斜杠续行）存储**：按 `Cycle detected:` 计真实条数（R1=2）。
5. **[fixround A1] rider 残留风险**（goal 已接受，仅记录）：若将来给 test-api 加真 `@Test` 却
   误配测试引擎（发现不到测试），任务会静默通过而非失败。补真测试者须同时接 `useJUnitPlatform()`。
   已写入 ADR-0005 Consequences。
6. **[fixround B3/A3] 卫生**：
   - r1 版 findings 曾含一个内网 MySQL 主机名；本轮已改为泛指（"内网 MySQL 主机"），并重写分支
     历史使该主机名**不存在于本分支任何 commit**（`git log -p origin/dev..HEAD` grep 该片段为空，
     见验收）。ADR-0005 与其余 committed 交付物已泛化，无内网主机名/IP。（注：基线 origin/dev 中另有
     P1 阶段的历史文档含同类泄漏，属既有基线内容、非本 goal commit，未在本分支范围改动。）
   - jdtls 在会话中生成的 Eclipse droppings（`.project` 等）已删；本轮无存活 jdtls 进程 root 于
     本 worktree（`ps` 核查），droppings 系上会话 LSP 索引副产物，删除后未再生成。
7. **[fixround r2 NB1] `OnlyCoreModules` 可能静默接受外部 jar → 收紧为「仅本地 build 产物」**：
   r1 版过滤器匹配裸段 `/rpc-client/` 等，而 Gradle cache jar 路径
   `.../files-2.1/tech.krpc/rpc-client/<ver>/<hash>/rpc-client-<ver>.jar` **也含** `/rpc-client/`；
   仓库未设 `preferProjectModules()`，未来传递版本 bump 可能把已发布的 `tech.krpc:rpc-client`
   外部 jar 顶替本地模块产物上 classpath，过滤器放行、包被正常分类、完整性守卫仍绿 → 静默范围替换。
   经诊断实测：本项目 project 依赖在 test runtime classpath 上呈**本地 `build/libs/*.jar`**
   （`.../wt-arch001/rpc-api/build/libs/rpc-api-1.1.0.jar!/...`），传递 ext-rpc 呈
   `.../caches/modules-2/files-2.1/tech.krpc.ext/ext-rpc/1.0.3/<hash>/ext-rpc-1.0.3.jar`。故不变量取
   **`/<module>/build/`** 段（本地产物路径形状，含 `build/libs` jar 与 `build/classes`），cache/`.m2`
   URI 无 `/build/` 段永不匹配。（评审建议的 `build/classes/java/main` 具体形状在本项目不适用——
   依赖是 `build/libs` jar，只匹配 class 目录会扫到 0 个类。）
   - **探针（plain JUnit，非冻结 `ScopeGuardTest`，强制）**：
     - `acceptsLocalBuildOutputRejectsCacheJar`：喂真实本地 build 产物 URI + 合成 cache jar URI
       (`.../files-2.1/tech.krpc/rpc-client/9.9.9/abc/rpc-client-9.9.9.jar`) + 合成 `.m2` jar URI →
       接受本地、拒绝两个外部。
     - `everyCoreModuleContributesLocalClassesOnly`：六模块各自本地类数 > 0，且无任何被分析类来自
       cache/`.m2` jar（未来静默替换 → 该模块本地类数 0 → RED）。
   - **red/green 实测**：临时把过滤器还原成裸 `/module/`（模拟 bug）→ `ScopeGuardTest` **FAILED**
     `must reject external Gradle-cache jar ==> expected: <false> but was: <true>`；还原为
     `/<module>/build/` → 全绿。store md5 `b260c707…` 未变（分析类集不变，冻结基线不动）。
   - build 依赖新增 `testImplementation org.junit.jupiter:junit-jupiter:5.11.4`（探针用 plain JUnit5）。
8. **[fixround r3 NB2] 把范围所有权锚定到本仓库绝对根（终结 scope-substitution）**：`/<module>/build/`
   只证明路径形状、不证明所有权——`/opt/vendor/krpc/rpc-client/build/...` 这类 composite/其他
   checkout 仍会被形状过滤器放行，且 sentinel 的 cache/.m2 判定看不见它。→ 改为**绝对前缀所有权**：
   test 运行时 repo 根 = arch-test 工作目录之父（Gradle 把 test JVM 工作目录设为该 project 目录，已实测：
   `user.dir=…/wt-arch001/arch-test`，parent=`…/wt-arch001`，`toRealPath` 与 classpath URI 前缀一致，
   无 macOS firmlink 偏移），`toRealPath()` 规范化。位置被拥有 ⇔ 其 URI 含 `<repoRoot>/<module>/build/`。
   一次性干掉整类：cache jar、.m2 jar、其他 checkout、迁移过的 buildDir 全部落在绝对前缀之外 → 拒绝、
   构建 RED。sentinel 的 isExternal 换成 `!isOwned(uri)`（绝对前缀之外即非拥有）。
   - **探针（`ScopeGuardTest`）**：`acceptsOwnedLocalOutputRejectsEverythingElse` 用**真实 repoRoot**
     构造 owned URI（接受），拒绝 cache jar + .m2 jar + 新增 other-checkout URI
     (`file:/opt/vendor/krpc/rpc-client/build/classes/java/main/...`)；
     `everyCoreModuleContributesOwnedClassesOnly`：六模块各自 owned 类数 > 0，且无任何被分析类非拥有。
   - **red/green 实测**：临时把过滤器还原成形状式（`/<module>/build/`，丢 repoRoot）→ `ScopeGuardTest`
     **FAILED** `must reject a different checkout's build output ==> expected: <false> but was: <true>`；
     还原为绝对前缀 → 全绿。store md5 `b260c707…` 未变；8 条冻结规则不变。
9. **[fixround r4 NB3] 表示不匹配的 false-RED → 全程用 `java.nio.Path` 比较**：r3 版把 repoRoot
   经 `toRealPath()`（物理路径）却当作**原始字符串前缀**去比 Location 的 URI 字符串（URI 编码）。
   symlink 的 projectDir → 逻辑/物理不符 → 全被拒（false RED）；含空格的 checkout 路径 → 原始空格 vs
   `%20` 不符 → false RED，且 `URI.create(带空格字符串)` 直接抛异常。→ 整个比较统一到 `java.nio.Path`：
   Location URI 取其磁盘文件（`jar:` URI 取 jar 文件、`file:` URI 取 class 文件，`Paths.get(URI)` 负责
   百分号解码）→ `canonical`（存在则 `toRealPath`，否则 `toAbsolutePath().normalize()`）→
   `Path.startsWith(<repoRoot>/<module>/build)`；非 file/jar 或不可解析 URI 一律**拒绝不崩**。
   - **探针（`ScopeGuardTest`，共 4 例全绿）**：owned/cache/.m2/other-checkout（URI 经 `toUri()` 构造，
     spaces-safe）；`handlesSpacesInPathWithoutCrashing`（在 owned build 目录下建真实含空格文件 →
     接受、不抛；外部含空格 `%20` 路径 → 拒绝、不抛）；`resolvesSymlinkedOwnedPathToOwned`（在非 owned 的
     arch-test/build 下建指向 rpc-api/build 的 symlink，经 symlink 访问 owned class → Path 版经 `toRealPath`
     解析为 owned 接受）。
   - **red/green 实测**：临时把 `includes` 还原成原始字符串前缀（NB3-buggy）→ `resolvesSymlinkedOwnedPathToOwned`
     **FAILED** `a symlinked view of an owned build output must resolve to owned ==> expected true`；还原为
     Path 比较 → 全绿（ArchitectureTest 8/0、ScopeGuardTest 4/0）。store md5 `b260c707…` 未变。
   - **收敛线（编排者决策，写入 ADR-0005 Limitations）**：所有 silent-green 范围替换轴已封闭；此后任何只会
     导致 false RED（构建响亮失败、绝不静默通过）的路径表示 exotica 属 **advisory 而非 blocking**。
10. **[fixround r5 NB4] 反向 symlink 锚点逃逸（真 false-green）→ 锚点必须落在 repo 根内**：若测试开始前
    把 `<repoRoot>/<module>/build` symlink 到外部位置，`canonical()` 会把外部目标登记为 owned → 外部类
    通过 `isOwned` → 静默通过。→ 构建 `OWNED_BUILD_DIRS` 时，每个锚点 canonical 后强制
    `canonical(anchor).startsWith(canonical(repoRoot))`，违反即 `IllegalStateException`（static init 抛 →
    整套响亮 RED）。
    - **探针**：`ScopeGuardTest.anchorEscapingRepoRootIsRejected` 直接测谓词 `requireInsideRoot`：根内锚点
      `assertDoesNotThrow`，根外锚点 `assertThrows(IllegalStateException)` 且消息含 "escapes the repo root"。
      （真把实际 build 目录搬到外部会破坏真实构建，disproportionate；按评审许可用谓词单测，合成根外路径。）
      probe 本身即 red/green（assertThrows=守卫触发的 RED，assertDoesNotThrow=green）。全绿：ArchitectureTest
      8/0、ScopeGuardTest 5/0；store md5 `b260c707…` 未变。
    - **威胁模型边界（编排者决策，写入 ADR-0005 Limitations）**：所有权链在 canonical Path 空间完全闭合
      （repoRoot → 锚点证在根内 → 类证在锚点内）。此后逃逸需 checkout 内超出「symlink build 目录」的对抗性
      文件系统操作（bind mount、FS race）——具此权限者可直接改测试本身，该类**超出本 gate 威胁模型、超出本 goal
      scope**。
11. **[fixround r6 NB5] intra-repo symlink 变体 → 锚点必须是普通目录（不再比目标）**：NB4 只验 canonical
    目标在 repo 根内；`rpc-api/build → ../rpc-common/build`（或 `→ build-old`）这类**仓内** symlink canonical
    后仍在根内 → 溜过 `requireInsideRoot`，静默替换/重复 scope。→ 构建 `OWNED_BUILD_DIRS` 时，对每个 module
    先 `requireNotSymlinked(root, module)`：`root.resolve(module)` 与其 `build/` 任一 `Files.isSymbolicLink`
    为真即 `IllegalStateException`（含具体路径）。检查基于 canonical root 之下的 lexical 路径，故根**之上**的
    symlink 祖先（如 macOS `/tmp → /private/tmp`）被容忍。NB4 的 under-root 检查保留为纵深防御。
    - **探针**：`ScopeGuardTest.symlinkedBuildAnchorIsRejected` 在 scratch temp 假根里建 `modx/build →
      ../mody/build`（拒绝）、`modlink → realmod` 模块目录 symlink（拒绝）、普通 `modok/build`（接受）；
      probe 本身即 red/green（assertThrows=守卫触发、assertDoesNotThrow=green）。真把实际 build 目录 symlink
      会破坏真实构建，disproportionate，按评审许可用 scratch temp 谓词单测。全绿：ArchitectureTest 8/0、
      ScopeGuardTest 6/0；store md5 `b260c707…` 未变。
    - **威胁模型收敛（写入 ADR-0005 Limitations）**：build-dir 级 symlink 重定向已 by construction 关闭（锚点须
      是普通目录），至此穷尽声明的威胁模型；再往下的逃逸需 checkout 内 bind mount / FS race 等对抗操作，超出
      本 gate 威胁模型与本 goal scope。

## 边界与状态

- 无 FOR/NOT FOR 边界变更；无生产代码变更；无依赖版本变更（仅新增 ArchUnit 及其测试传递依赖）；
  无 publish/upload.gradle 变更（除「不 apply=不发布」）。
- 新增 ADR-0005（accepted）。无既有 ADR/roadmap/module 状态需改。发布模块集不变。
