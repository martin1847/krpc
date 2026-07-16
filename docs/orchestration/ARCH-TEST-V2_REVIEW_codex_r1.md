# ARCH-TEST-V2 r1 — adversarial review

Reviewed `8a11a7c` against `origin/dev`, cold context. Scope is the new `arch-test`
plain gates, the two owning-module test files, and the ADR/implementation records. The
contracts under review are: complete ArchUnit scan face, no service-author `.proto` in
production source, JSON on the actual default wire path (NS-4), and MCP/agent exposure
defaulting OFF (NS-6). Main risk: a check can pass while the controlled surface has moved
outside the data it actually observes.

## Findings

### 1. `settings.gradle` parser silently ignores valid include forms

- **Location:** `arch-test/src/test/java/tech/krpc/arch/ScanFaceCompletenessTest.java:85-95`
- **Severity:** blocking
- **Evidence:** `settingsIncludes()` only considers a physical line whose trimmed prefix is
  `include` and extracts only single-quoted strings. Gradle accepts, among others,
  `include "rpc-phantom"`, `include("rpc-phantom")`, and multiline invocations. Existing
  single-quoted lines still supply `rpc-api`, so the sanity assertion remains green while a
  newly included double-quoted module is absent from `includes`.
- **Failure scenario:** add `include "rpc-phantom"`, give that module a production class
  violating R1--R4, and do not add it to `MODULES`/`testImplementation`. Gradle includes and
  builds it; this test never sees it and passes. The claimed anti-vacuous-green gate is then
  bypassed. By contrast, the documented probe using `include 'rpc-phantom'` would fail as
  claimed: lines 123-131 compute it as unclassified and name it in the failure message.
  The explicit `EXCLUDED` set is not the problem—there is no broad `ext-*`/`*-spring`
  regex that could swallow a new name.
- **Suggested fix:** derive included project paths from Gradle's evaluated project model in a
  build-level verification task, or feed that model to the test. If static parsing must
  remain, implement and self-test a defined grammar covering single/double quotes,
  parentheses, multiline calls, and comments; reject unsupported `include` syntax rather
  than silently omitting it.

### 2. NS-1 has the same module-discovery bypass and assumes default project directories

- **Location:** `arch-test/src/test/java/tech/krpc/arch/NoProtoInProductionSourceTest.java:58-73, 83-88`
- **Severity:** blocking
- **Evidence:** `productionModules()` repeats the single-quoted, line-local parser. It then
  maps a Gradle path to `repoRoot/<path-with-colons-replaced>` instead of using Gradle's
  resolved `Project.projectDir` and production source-set directories.
- **Failure scenario:** a new module is declared with `include("rpc-phantom")` (or is given
  a custom `projectDir`), contains `src/main/proto/service.proto`, and is otherwise normal.
  The test omits it (or looks in a non-existent default directory), so it passes with a
  hand-authored production proto. The current walk does cover all files below a conventional
  discovered module's `src/`, including `src/main/resources`; it is the discovery/path
  assumption that leaves new/custom source sets outside the gate.
- **Suggested fix:** share the authoritative Gradle project/source-set inventory used by the
  build, then walk every production source directory for each included production project.
  At minimum, fail when an include cannot be parsed or its expected project directory is
  absent; do not treat it as an empty production module.

### 3. MCP default-OFF test never observes Quarkus configuration injection

- **Location:** `rpc-server-quarkus/src/test/java/tech/krpc/server/agent/McpDefaultOffContractTest.java:51-70`
- **Severity:** blocking
- **Evidence:** each test constructs a handler directly. Java initializes `boolean
  mcpEnabled` to `false` independently of `@ConfigProperty`; no Quarkus/SmallRye injection
  occurs. Changing both production annotations at `McpHandler.java:74` and
  `McpGetHandler.java:35` from `defaultValue = "false"` to `defaultValue = "true"` leaves
  these assertions green. The `h.mcpEnabled = true` flip only proves the `enabled()` branch,
  not that an unconfigured runtime injects false.
- **Failure scenario:** an operator starts an unconfigured Quarkus server after the annotation
  default is changed to true. Both `/mcp` handlers are injected true and registered, while
  this suite still reports default OFF.
- **Suggested fix:** assert the runtime-retained `ConfigProperty.defaultValue()` on both
  fields (no CDI container is needed), and preferably add a minimal configuration/integration
  test proving an unconfigured container does not expose the route. Keep the true-flip as the
  non-vacuity check.

### 4. NS-4 test does not exercise the `InputProto` default wire path it claims to pin

- **Location:** `rpc-client/src/test/java/tech/krpc/client/DefaultCodecJsonTest.java:34-46`
- **Severity:** blocking
- **Evidence:** the test only reads `SerialEnum.JSON_VALUE` and the registry mapping for the
  literal `0`; it never creates/parses `InputProto` or feeds its unset codec value into the
  decode path. Its Javadoc says `proto/internal.proto e=0`, but the checked-in
  `proto/internal.proto:13-16` has no `e` field or `SerialEnum` (the field is commented out).
  The generated Java used by production instead contains `getEValue()` and is consistent with
  `proto/internal.java.proto:34-47`. Thus the test can stay green while the declared proto
  source and the actual default-envelope path drift apart.
- **Failure scenario:** an envelope/generator change makes an unset input select a different
  codec path (or removes/relocates the codec field while preserving `SerialEnum.JSON_VALUE`
  and `Serial.Instance.get(0)`). All three assertions remain green, but a default request no
  longer demonstrably decodes as JSON.
- **Suggested fix:** test `InputProto.getDefaultInstance()` (including serialize/parse) and
  pass its codec value through the same resolver/reader used by server dispatch; assert the
  result is JSON. Also resolve which top-level proto is canonical and make the test reference
  that source of truth rather than the contradictory `proto/internal.proto` comment.

### 5. Spring production adapters remain outside R1--R4

- **Location:** `docs/decisions/ADR-0005-architecture-gates.md:246-253` and
  `arch-test/src/test/java/tech/krpc/arch/ScanFaceCompletenessTest.java:59-76`
- **Severity:** advisory
- **Evidence:** original ADR-0005 explicitly limits ArchUnit ownership to the named six core
  modules, so excluding `rpc-client-spring` and `rpc-server-spring` is not a hidden weakening
  of that accepted decision. The addendum prominently names both adapters, explains the
  Quarkus/Spring asymmetry, and the explicit list forces a conscious future decision. NS-1
  still scans those adapters for proto files.
- **Failure scenario:** a Spring adapter accumulates a core-layer, infrastructure, or
  JDK-internal violation; R1--R4 do not observe it because it is intentionally out of scope.
- **Suggested fix:** retain the explicit exclusion, but create/track a policy review trigger
  for adapter runtime ownership (or add a separately scoped adapter gate) before more runtime
  behavior is placed there.

## Freeze, scope, and execution evidence

- `git diff --exit-code origin/dev -- arch-test/archunit_store archunit.properties` exited
  zero: frozen store and properties are unchanged.
- The commit changes only `arch-test`, the two requested owning-module test paths, and docs;
  no production source or existing rule/test was modified. `git diff --check` was clean.
- `/opt/gradle/gradle/bin/gradle :arch-test:test --rerun-tasks --max-workers=2` —
  `BUILD SUCCESSFUL`.
- `/opt/gradle/gradle/bin/gradle :rpc-client:test :rpc-server-quarkus:test --rerun-tasks
  --max-workers=2` — `BUILD SUCCESSFUL`.
- The single-quoted scan-face self-proof is plausible from the implementation: a real
  `include 'rpc-phantom'` reaches the unclassified-set assertion and fails with the module
  name. A double-quoted dynamic probe was not obtained because a separate Gradle invocation
  failed before configuration while loading `libnative-platform.dylib`; the bypass follows
  directly from the parser and does not depend on that failed invocation. The temporary
  settings edit and directory were restored; no implementation-file residue remains.

## Verdict

**REQUEST-CHANGES** — multiple claimed guard classes can be bypassed or pass without
observing their stated runtime/wire contract, so the required anti-vacuity guarantee is not
yet established.

## r2 closure

Reviewed closure commit `0d0d2e4` only against the r1 findings.

- **r1 #1 / #2 (Gradle include and real source locations): partially closed, but still
  blocking.** `BuildInventory` now consumes Gradle's evaluated project model, records real
  `projectDir` plus main source roots, and `NoProtoInProductionSourceTest` walks both those
  roots and `<projectDir>/src`. `BuildInventory` fails loudly when its property, directory,
  or either inventory file is missing; `:arch-test:test` declares a dependency on the writer.
  However, `writeArchInventory` declares only outputs
  (`arch-test/build.gradle:52-61`) and no inputs representing `settings.gradle`, project
  paths/directories/source sets, or `testImplementation` project dependencies. After the
  requested rerun generated the TSV, a normal
  `gradle :arch-test:writeArchInventory --info --max-workers=2` reported the task
  **UP-TO-DATE**. Editing `settings.gradle` (or a project build script that changes a source
  set) can therefore leave the old TSV in place; a normal `:arch-test:test` can consume it
  and miss a new project—the original vacuous-green class. Declare the evaluated inventory
  content as a task input (and/or all contributing settings/build files) so a model change
  forces regeneration, then self-prove a non-`--rerun-tasks` include edit goes red.
- **r1 #3 (NS-6): closed.** Both handler fields are now reflected as runtime-retained
  `@ConfigProperty`, checking flag name and `defaultValue == "false"`; an annotation flip
  is observable. The explicit true flip still proves the gate branch.
- **r1 #4 (NS-4): closed.** The test parses an empty wire envelope via
  `InputMarshaller.parse`, asserts its decoded `getEValue()`, and feeds that value through
  the same `Serial.Instance.get(arg.getEValue())` resolver used by dispatch.
- **r1 #5 (Spring scope): closed as advisory.** ADR-0005 now contains the requested policy
  review trigger before adapter runtime behavior grows.
- **Freeze discipline:** `git diff --exit-code origin/dev -- arch-test/archunit_store
  archunit.properties` exited zero.
- **Execution:** `/opt/gradle/gradle/bin/gradle :arch-test:test :rpc-client:test
  :rpc-server-quarkus:test --rerun-tasks --max-workers=2` completed `BUILD SUCCESSFUL`.

**REQUEST-CHANGES** — the evaluated-model design is correct when regenerated, but its writer
is allowed to go stale during an ordinary test invocation, recreating the scan-face false
green that r1 required the gate to eliminate.
