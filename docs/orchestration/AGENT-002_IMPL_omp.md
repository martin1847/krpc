# AGENT-002 — MCP Error Envelope + DX (Implementation Findings)

Owner: omp. Branch `feat/agent-002` (from origin/dev @ 5e5b120). ADR-0004 (agent surface),
NS-6 (flags default OFF), NS-7 (native-image). Reviewer: codex. Date: 2026-07-17.

Consumer field-test driven: a consumer agent drove the MCP face end-to-end on staging
(interface-level `agentTool=true`, 4 tools, real data). Findings below are verified against
code, not taken on the field report's word.

## What changed

| Area | File | Change |
| --- | --- | --- |
| Error envelope | `rpc-server-quarkus/.../agent/McpHandler.java` | `tools/call` error paths (thrown status + non-zero `RpcResult`) now emit `{code,message,violations?}` JSON in the tool-result content, `isError:true`. Jakarta violations parsed from the dispatch's `INVALID_ARGUMENT` description. |
| did-you-mean / empty-face | `McpHandler.java`, `McpToolRegistry.java` | Unknown-tool `-32602` message appends nearest tool names (Levenshtein ≤ 2, max 3); empty `tools/list` carries a `_meta` hint. `McpToolRegistry.toolNames()` added. |
| serverInfo | `McpHandler.java` | `name` = exposed app name (`ApiMeta.app`); `version` = jar `Implementation-Version` (fallback `RpcConstants.VERSION`). Was hardcoded `"krpc"` / `RpcConstants.VERSION`. |
| Description honesty | `McpSchema.java` | No `@Doc` → no more `Service.method` echo as the description head; DTO field `@Doc` still flows into schema. |
| Method-level agentTool | `rpc-api/.../annotation/UnsafeWeb.java`, `rpc-server/.../RpcServerBuilder.java` | New nested `@UnsafeWeb.AgentTool` (`@Target(METHOD)`, `RUNTIME`). Scan exposes a method to MCP when interface-level `agentTool=true` **or** the method carries `@AgentTool`. Defaults all-OFF (NS-6). |
| Build | `rpc-server-quarkus/build.gradle` | `jar` manifest stamps `Implementation-Version = project.version` so `serverInfo.version` reports the real build version. |
| Tests | `McpErrorEnvelopeTest` (new), `McpMethodAgentToolScanTest` (new), `AgentTestServices`, `McpHandlerTest`, `McpBridgeQuarkusTest` (quickstart) | Red-first envelope/did-you-mean; method-level scan matrix; updated serverInfo + business-failure contracts. |

## Root causes (file:line, verified)

1. **Missing required param → bare `"StatusRuntimeException"`.** `ValidatorInvoke.java:37-41`
   raises `Status.INVALID_ARGUMENT.withDescription("Dto : path=value(message);…")` — the field
   **is** in the status description. `UnaryMethod.invokeWeb` (`UnaryMethod.java:192-206`) rethrows
   it verbatim (no wrapping). The loss was purely at the MCP edge: old
   `McpHandler.toolsCall` catch did `toolError(id, ex.getClass().getSimpleName())` → the class
   name, discarding the description. Fixed: `envelopeFromThrowable` recovers `Status.fromThrowable`
   (code + description) and `parseViolations` turns the description into `[{field,constraint,rejected?}]`.
2. **Wrong entity id → bare `"NOT_FOUND"` / bare message, no envelope.** Two paths, both flattened
   the envelope: (a) a thrown business `Status` collapsed to the class name in the same catch as
   #1; (b) a non-zero `RpcResult` (`McpHandler.toolResult`, old line 293-297) emitted
   `output.getM()` (or `"code N"`) as a bare content string. Both now build a `{code,message}`
   envelope; `code` is the gRPC status code value (thrown) or the `RpcResult` code (business).
3. **Unknown tool → no suggestion; empty face → no hint.** Old `toolsCall` returned
   `"Unknown tool: " + name`; `toolsList` returned `{tools:[]}` with no explanation. Fixed:
   `unknownToolMessage` (Levenshtein) + `_meta` hint on empty `tools/list`.
4. **Description = `Service.method` when no `@Doc`.** `McpSchema.description` (old line 76-88) fell
   back to `api.getName()+"."+m.getName()`. Removed; only the honest RpcResult-envelope note is
   emitted when `@Doc` is absent. DTO field-level `@Doc` path (`applyConstraints` "Doc" case) is
   untouched.
5. **serverInfo `name:"krpc"` / `version:"1.0.0"`.** Old `initialize` (line 180-181) hardcoded
   both. `name` now = `registry.apiMeta().getApp()` (= `ServerContext.applicationName`, what
   `RpcServerBuilder` stamps via `RpcMetaServiceImpl.buildApiMeta:82`); `version` now =
   `McpHandler.class.getPackage().getImplementationVersion()` fallback `RpcConstants.VERSION`.

## Per-item decisions

### 1. Error envelope — slot choice (MCP spec compliance)

MCP spec 2025-06-18 (server/tools §Error Handling): tool execution errors are **unstructured**
`content` + `isError:true`; `structuredContent` is reserved for **outputSchema-conformant success
data**. Decision: the error envelope is serialized as the JSON `text` of a single text content
block; it is **NOT** placed in `structuredContent` (that would break clients that validate
`structuredContent` against the success `outputSchema`). Unknown tool stays a JSON-RPC protocol
error (`-32602`), per the spec's own "Unknown tool" example.

`code` is an integer (gRPC status code value on the thrown path, `RpcResult` code on the business
path), matching the documented `RpcResult {code,message,data}` envelope (code 0 = success). When a
thrown status has no description, `message` falls back to the status code name (e.g. `"NOT_FOUND"`).

### 2. did-you-mean + empty-face hint; flag-OFF 404-hint **DECLINED**

- did-you-mean: `Levenshtein ≤ 2`, nearest-first, max 3, sourced only from names already returned
  by `tools/list` (no new disclosure). Empty face → the message says
  `"0 tools registered: KRPC_MCP enabled but no @UnsafeWeb(agentTool=true) interfaces"`.
- empty `tools/list`: hint in `result._meta["tech.krpc/hint"]`. `_meta` is the spec-compliant slot
  on any MCP `Result`; `instructions` exists only on the `initialize` result, not `tools/list`, so
  it was not used. The list stays valid (`tools: []`).
- **flag-OFF 404-with-hint: DECLINED.** ADR-0004 (decision bullet, ADR-0004:94-95) states the
  default-OFF flag = **"byte-level zero new surface"**, and `agent-guide.md:260` documents that with
  MCP OFF, `POST /mcp` and `GET /mcp` are **absent (404)** — that 404 is the raw netty transport's
  unknown-path response, not a krpc-emitted body. Emitting *any* `/mcp` response body when OFF
  (even a hint) requires registering the path, which produces new bytes on the wire in the OFF
  state and directly violates the byte-level-zero invariant and NS-6. The OFF gate
  (`McpHandler.enabled()`) is left untouched. A consumer who gets a bare 404 on `/mcp` has MCP
  disabled — that is the documented, intended signal; the discoverability fix belongs to docs, not
  to a wire response.

### 3. Description honesty + example-input skeleton

- No-`@Doc` name echo removed (above).
- **Example-input skeleton: DROPPED, not cheap.** MCP has no standard per-tool "example" slot;
  it would live in `_meta` or the description prose. Generating placeholder values requires a
  recursive walk of the schema tree with per-type placeholder heuristics and cycle handling —
  real complexity in `McpSchema` for marginal value, since `inputSchema` (types + jakarta
  constraints + enums + `@Doc`) is already the machine-usable contract MCP clients render forms
  from. Not cheap → dropped, as the task permits.

### 4. serverInfo version source

`getImplementationVersion()` reads the runtime jar's `Implementation-Version` manifest attribute,
now populated by `rpc-server-quarkus/build.gradle`. Confirmed on the native runner:
`serverInfo = {name:"quickstart", version:"1.1.0"}` — the real `project.version`, not a hardcode.
Falls back to `RpcConstants.VERSION` when the manifest is absent (plain unit tests / exploded
classpaths). Note: `RpcConstants.VERSION` is itself stale (`"1.0.0"` vs `project.version` 1.1.0)
— left as-is (rpc-common is out of this scope); the manifest path is now the source of truth for
`serverInfo`, so the stale constant only shows in the fallback (unit tests).

### 5. Method-level `@UnsafeWeb.AgentTool`

Semantics (NS-6, all-OFF by default):
- interface `@UnsafeWeb(agentTool=true)` → every method exposed (unchanged; the nested annotation
  is then redundant);
- interface `@UnsafeWeb` (agentTool defaults false) → only `@AgentTool`-annotated methods exposed;
- no annotation → no MCP exposure;
- `@AgentTool` on a non-`@UnsafeWeb` interface has no effect (MCP exposure ⊆ web exposure; the
  method-level check runs inside the existing `if (needMeta && web)` block).

Scan change: `RpcServerBuilder.java` computes `agentTool` per method as
`ifaceAgentTool || stub.method.isAnnotationPresent(UnsafeWeb.AgentTool.class)` (the reflective
interface `Method` is on `MethodStub.method`).

## Verification

- **Red-first captured** (dev, before impl): `McpErrorEnvelopeTest` — 5/5 RED. Cases 1+2 failed as
  the bare content string (`"StatusRuntimeException"`) is not JSON, so the envelope parse threw;
  unknown-tool and empty-face RED for the missing suggestion/hint. After impl: 5/5 GREEN.
- `:rpc-server:test :rpc-server-quarkus:test :http-server:test :arch-test:test` — GREEN
  (`--rerun-tasks --max-workers=2`). `:examples:quickstart:test` — GREEN. arch-test frozen rules
  did **not** fire (no store touched).
- New method-scan matrix: `McpMethodAgentToolScanTest` — 6/6 GREEN (subset exposed, sibling not,
  interface-level still all, no-annotation zero, web surface unaffected, ApiMeta filtered).
- **NS-6 defaults unchanged** — cited assertions: `UnsafeWeb.agentTool()` `default false`
  (annotation source); `McpAgentToolGateTest` asserts an `@UnsafeWeb(agentTool=false)` method is
  gated out of MCP dispatch + `tools/list`; `McpMethodAgentToolScanTest.noAnnotation_noMcpExposure`
  asserts `@UnsafeWeb` alone yields zero MCP exposure; `KRPC_MCP` default OFF via
  `McpHandler.enabled()` `@ConfigProperty(defaultValue="false")` and the quickstart
  `McpDisabledQuarkusTest` (404 when OFF), unchanged.
- **NS-7** — main sources changed (RpcServerBuilder / McpHandler / McpSchema / McpToolRegistry /
  UnsafeWeb): quickstart native image rebuilt (GraalVM 25, host toolchain, `-Dquarkus.native.enabled=true`,
  `-x test`) — **BUILD SUCCESSFUL in 1m17s**, booted (`quickstart 1.1.0 native … started in 0.043s`).
  Smoked with `KRPC_MCP=true`:
  - `initialize` → `serverInfo {name:"quickstart", version:"1.1.0"}`.
  - `tools/call Hello_hello {}` (missing `@NotBlank name`) →
    `{"code":3,"message":"HelloRequest : name=null(must not be blank)","violations":[{"field":"name","constraint":"must not be blank"}]}`, `isError:true`.
  - `tools/call Hello_helo` (typo) → `-32602 "Unknown tool: Hello_helo. Did you mean: Hello_hello?"`.

## Not validated / out of scope

- Empty-face `_meta` hint smoke-tested only via unit test (quickstart's single tool is agentTool,
  so it has no empty face to boot).
- `RpcConstants.VERSION` staleness: FIXED in fix round r1 (F2) — now derives from the generated
  `BuildVersion` (project.version), no hand-maintained duplicate.
- Wire: fix round r1 (F1) changed the gRPC validation `INVALID_ARGUMENT` description to the
  generic `"Invalid input"` (stops the same rejected-value leak on the gRPC face). No proto /
  serialization / NS-4 byte-format change. No otel / arch-test / SPEC.md / rpcurl changes.

---

## Fix round r1 (codex REQUEST-CHANGES → amended into the single commit)

Review: `docs/orchestration/AGENT-002_REVIEW_codex_r1.md`. Three findings addressed; the
durable typed fix replaces the r0 string-reparse approach.

### F1 (CRITICAL) — validation failures disclosed rejected secret values → FIXED (durable)

Root: r0 copied `ValidatorInvoke`'s Status description (`Dto : path=value(message)`) into
`message` and re-parsed the value into `violations[].rejected` — the rejected value (a possible
password/token) reached the MCP content, and it was already on the gRPC wire/logs too.

Durable fix — carry validation data TYPED, never as display text:
- New `rpc-server/.../invoke/ValidationException` (extends `StatusRuntimeException`): holds
  `List<Violation{field,constraint}>`; Status = `INVALID_ARGUMENT` with a **generic** description
  `"Invalid input"`. The rejected value (`ConstraintViolation.getInvalidValue()`) is never read.
- `ValidatorInvoke` now throws `ValidationException` built from `getPropertyPath()` +
  `getMessage()` only — no value in the description, the wire, or logs.
- `McpHandler.envelopeFromThrowable`: violations come ONLY from the typed carrier
  (`findValidation` walks the causal chain); envelope = `{code, message:"Invalid input",
  violations:[{field,constraint}]}`. The **`rejected` key is gone entirely** (field+constraint
  is enough for agent self-correction — the agent knows what it sent).
- Tests: `rpc-server/.../ValidatorInvokeSecretTest` — a stub `ConstraintViolation.getInvalidValue()`
  returns a secret; asserts it appears NOWHERE in the thrown exception (status description +
  typed violations + message). `McpErrorEnvelopeTest.toolsCall_validationFailure_rejectedSecretAppearsNowhere`
  — a secret sent as a field value is absent from the full serialized MCP response.

### F2 (MAJOR) — `serverInfo.version` wrong on the manifest-absent fallback → FIXED

Root: fallback was `RpcConstants.VERSION = "1.0.0"` (hand-maintained, stale vs `gradle.properties`
1.1.0).

Fix — one build-version source of truth: `rpc-common/build.gradle` `generateBuildVersion`
generates `tech.krpc.common.BuildVersion` (a compile-time `String` constant = `project.version`,
inlined into bytecode → native-image safe, no runtime resource lookup). `RpcConstants.VERSION`
now **derives** from `BuildVersion.VERSION` (no duplicate). `krpcVersion()` keeps the jar
`Implementation-Version` as primary and this generated value as the fallback, so both paths report
the real version.
- Tests: `McpHandlerTest.krpcVersion_fallbackIsGeneratedProjectVersion_notStaleHardcode` —
  exploded test classpath (manifest absent) asserts `krpcVersion() == BuildVersion.VERSION` and
  `RpcConstants.VERSION == BuildVersion.VERSION`.
- Native re-smoked THIS round (socket-bind succeeded here, unlike the reviewer's sandbox):
  `initialize` → `serverInfo {name:"quickstart", version:"1.1.0"}`.

### F3 (MEDIUM) — prose parser invented violations → FIXED

The description string parser (`parseViolations`) is **deleted**. Violations now come only from
the typed `ValidationException`; any other `Status` yields `{code, message}` with NO `violations`
key.
- Tests (`McpErrorEnvelopeTest`): business `INVALID_ARGUMENT` prose
  `"Order : state=locked(retry later); note=a;b(x)"` → no phantom violations, message verbatim;
  malformed-JSON-style description → no violations; nested `ValidationException` cause → typed
  violations still recovered (matches `Status.fromThrowable` chain-walk).

### gRPC-wire note (in scope, positive side effect)

Changing `ValidatorInvoke` to the generic description also stops the gRPC face from leaking the
rejected value in its `INVALID_ARGUMENT` description — the same disclosure existed there. No
test asserted the old detailed gRPC description; searched `test-server`/`rpc-client`/`test-api`.

### Fix-round verification

`:rpc-common:test :rpc-server:test :rpc-server-quarkus:test :http-server:test :arch-test:test
:examples:quickstart:test --rerun-tasks --max-workers=2` — all GREEN (arch-test frozen rules did
not fire). New: `ValidatorInvokeSecretTest` 2/2, `McpErrorEnvelopeTest` 9/9. Native rebuilt
(GraalVM 25, 1m20s) + booted + smoked F1/F2 as above; the quickstart `@QuarkusTest` also
exercises the REAL hibernate-validator → `ValidationException` → envelope path.

---

## Fix round r2 (codex closure — cross-face regression, amended into the single commit)

### Blocking — r1's generic description broke the CLASSIC gRPC face → FIXED

Root: r1 set `ValidationException`'s status description to a flat `"Invalid input"`. The typed
`violations()` are JVM-local, so a **remote classic gRPC client** (which only sees `io.grpc.Status`)
lost field-level detail — regressing the active classic-face contract that `INVALID_ARGUMENT` is
field-level self-correctable.

Fix (reviewer's primary suggestion): `ValidationException` now builds its status **description**
from the typed violations — `"Dto : field(message)[; field2(message2)]"` (semicolon-joined,
field + jakarta constraint message only, **never** a rejected value). One source: `describe()`
inside `ValidationException`; `ValidatorInvoke` passes `input.getClass().getSimpleName()` + the
`{field,constraint}` list. `getInvalidValue()` is still never read anywhere.
- MCP face unchanged: `McpHandler` keeps consuming the typed `violations()`; envelope stays
  `{code, message:"Invalid input", violations:[{field,constraint}]}` (no `rejected`).
- Classic face restored: the remote status description carries the field-level detail again.

Tests:
- `rpc-server/.../ValidationRemoteRoundTripTest` — a REAL in-process round-trip (netty
  `RpcServerBuilder` server + `RpcClientFactory` client over a `ManagedChannel`, the
  `OtelProductionChainTest` pattern). Client sends `password=<secret>`; asserts the remote
  `StatusRuntimeException` description is `INVALID_ARGUMENT` and contains `email(must not be
  blank)` (+ the password constraint), AND that the secret appears NOWHERE in the remote status
  description / cause / trailers.
- `ValidatorInvokeSecretTest` updated: description is now `Dto : field(message)`, value-free
  (stub `getInvalidValue()` returns a secret to prove it is never read); multi-violation `;`-join.
- Shared jakarta stubs extracted to `rpc-server/.../invoke/ValidationTestStubs` (used by both
  the unit and round-trip tests; no hibernate-validator dependency added).
- MCP secret-absence + envelope tests unchanged and green (constructor call sites updated to
  `new ValidationException(dtoName, violations)`).

Note: this also keeps the classic gRPC wire value-free (the r1 leak fix holds on both faces).

### r2 verification

`:rpc-common:test :rpc-server:test :rpc-server-quarkus:test :http-server:test :arch-test:test
--rerun-tasks --max-workers=2` — all GREEN; arch-test frozen rules did not fire.
`ValidationRemoteRoundTripTest` 1/1, `ValidatorInvokeSecretTest` 2/2, `McpErrorEnvelopeTest` 9/9.
