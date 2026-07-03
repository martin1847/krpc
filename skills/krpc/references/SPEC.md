# KRPC Development Spec

A tool-agnostic handbook for any coding agent (Claude, Codex, …) **writing or
calling KRPC services**. It encodes the framework's authoring conventions with
code evidence (`file:line`). Governance/architecture authority lives in
[`AGENTS.md`](AGENTS.md) + ADRs + `docs/`; when they conflict with this file,
they win. Build/release is in [§12](#12-build-test-release).

KRPC is **interface-first**: you write a Java interface + DTOs; the framework
handles transport (gRPC/HTTP2), JSON serialization, validation, metadata, and
client generation. Service authors write **no proto files**.

---

## 1. The method contract (the one rule that bites first)

Every RPC method MUST be:

```java
RpcResult<SomeDto> methodName(OneDto req)   // exactly one param
RpcResult<SomeDto> methodName()             // or zero params
```

Two hard constraints, both enforced at the **only** method-discovery point
(`rpc-common/.../util/RefUtils.java:117-120`):

```java
.filter(m -> m.getReturnType() == RpcResult.class && m.getParameterCount() <= 1)
```

- **Return type must be `RpcResult<…>`.**
- **At most one parameter.**

A method violating either is **silently dropped** — not registered, no error at
startup; the call just fails to resolve at runtime. This is the single most
common mistake.

- **DO:** merge multiple inputs into one DTO. Built-in generic wrappers exist:
  `PagedQuery<T>` (`rpc-api/.../PagedQuery.java`), e.g. `plistBk(PagedQuery<Book> q)`.
- **DON'T:** `m(Long id, String name)` (two params) or `SomeDto m(...)` (raw return).

The wire protocol has a single input slot; the server reads only
`inputArgTypes[0]` (`rpc-server/.../UnaryMethod.java:70-71`).

---

## 2. RpcResult — the response envelope

`rpc-api/.../model/RpcResult.java`. Three fields, success/failure mutually
exclusive:

| field | meaning |
| --- | --- |
| `int code` | `0` = OK; `> 0` = business error. **No negative codes.** |
| `String msg` | non-null only when `code > 0` |
| `DTO data` | non-null only when `code == 0` |

```java
return RpcResult.ok(data);          // asserts data != null
return RpcResult.error(666, "...");  // asserts code > 0 && msg != null
result.isOk();                       // code == 0
result.ifOk(fn); result.orElseThrow();
```

Business error codes are **bucketed by hundreds; large domains by thousands**
(`RpcResult.java:21-22`). The numeric space is a superset of `google.rpc.Code`
(see `CommonCode`).

Callers read `data` only after `isOk()` (`rpc-client/.../ClientResult.java:32-41`).

- **DO:** `ok(nonNullData)` / `error(positiveCode, msg)`.
- **DON'T:** return a bare DTO, use negative codes, set `msg` on success or
  `data` on failure, or call `ok(null)` (assertion fails).

---

## 3. Error model — soft vs hard exceptions

Two **different channels**. Pick deliberately.

### Soft (business failure) → return, don't throw
Expected business failures return through `RpcResult.code`. The client gets a
normal `RpcResult` with `isOk()==false`.

```java
public RpcResult<Integer> testLogicError(Integer i) {
    return RpcResult.error(666, "业务逻辑失败");   // DemoServiceImpl.java:43-46
}
```
`RpcResult.java:22-24` is explicit: **do not use Java exceptions to convey
business errors — define an error code instead.**

### Hard (system/security/validation) → throw
System errors, auth failures, validation failures, and unexpected
`RuntimeException`s are thrown. The server catches everything
(`UnaryMethod.java:212-231`): a `StatusException`/`StatusRuntimeException` passes
through as-is; anything else is wrapped in `Status.UNKNOWN` with the message
**truncated to 100 chars** and an error log keyed by `traceId`.

| | Soft | Hard |
| --- | --- | --- |
| trigger | business rule unmet | system / security / validation / unexpected |
| express | `return RpcResult.error(code,msg)` | `throw` (prefer `Status.X.withDescription(..).asRuntimeException()`) |
| code | business code (hundreds/thousands) | gRPC Status code |
| client sees | normal `RpcResult`, `!isOk()` | gRPC `onError` / `StatusRuntimeException` |
| logged | no | `log.error(traceId, ex)` |

- **DON'T:** throw for business errors; rely on exception messages reaching the
  client intact (they're truncated to 100 chars).
- Document codes with `@Doc.ErrorCode(code=, when=, message=)` (`Doc.java:37-52`).

---

## 4. DTO rules

### Use boxed types, never primitives
JSON serialization is `NON_NULL` (`rpc-common/.../util/JsonUtils.java:24-26`):
null fields are **omitted**. Primitives can't be null and serialize as `0`/`false`,
destroying the "absent vs zero" distinction.

```java
public class Book {            // Book.java:10-17
    Integer id; Float lng; Float lat;   // boxed, not int/float
}
```
- **DO:** `Integer/Long/Boolean/Float/Double` for all scalar DTO fields.
- **DON'T:** `int/long/boolean` — loses null semantics, breaks convention.

### Type avoidance
- **Avoid `Map`** in responses (functional tests only; not for production —
  `DemoService.java:41`).
- **Avoid `enum`** in response DTOs — adding/removing values breaks old clients
  (`README:87`).
- **`byte[]`** is the one type passed bare on the wire, but **TS/Dart clients
  cannot send `byte[]` as input** (`DemoService.java:33`).
- Generic DTOs are supported (`PagedQuery<T>`, `PagedList<T>`, `List<T>`); the
  server resolves type args by reflection on the method signature.

### Deserialization is lenient
`FAIL_ON_UNKNOWN_PROPERTIES=false` — extra fields from clients are tolerated
(forward compatibility). `JavaTimeModule` auto-registers if jsr310 is present.

---

## 5. @RpcService and service naming

`@RpcService` on the **interface** (`rpc-api/.../annotation/RpcService.java`).
Attributes: `value` (override name), `version`, `description`, `expireSeconds`.

Name derivation (`RefUtils.java:126-151`):
- strip leading `I`: `IDemoService` → `Demo`
- strip `Service`/`Rpc` suffix: `DemoService` → `Demo`, `FooRpc` → `Foo`
- full name = `appName/ServiceName` (e.g. `test-server/Demo`)
- **no dots** allowed in a service name

Call path is `app/Service/method`.

---

## 6. @UnsafeWeb — web exposure + hiding

`@UnsafeWeb` (TYPE-level, `rpc-api/.../annotation/UnsafeWeb.java`) marks a service
**reachable directly by browsers/frontend**. Without it, the service name is
prefixed with `-` (`HIDDEN_SERVICE`, `RefUtils.java:124-150`) so the HTTP gateway
keeps it service-to-service only.

```java
@UnsafeWeb                              // frontend-reachable
@UnsafeWeb(requireCredential = true)    // + JWT required for all methods
@UnsafeWeb.RequireCredential            // method-level JWT requirement
```
Credential precedence: method `@RequireCredential` > class `requireCredential` >
default false (`UnaryMethod.java:58-64`). When required, `ctx.checkCredential()`
runs and throws (hard) on failure.

- **DO:** add `@UnsafeWeb` only to services the frontend must call directly.
- **DON'T:** add it to internal services (exposes them publicly). The name
  "Unsafe" is a reminder that **you** own the security hardening.

---

## 7. Validation

Uses **`jakarta.validation`** (not `javax.validation` — only the `jakarta`
prefix is scanned, `RefUtils.java:158-175`). A validator is attached only when a
DTO has constraint annotations; failures throw `INVALID_ARGUMENT` (hard,
`ValidatorInvoke.java:31-44`).

```java
public class PagedQuery<Q> {                 // PagedQuery.java:28-36
    @NotNull @Min(1) Integer page;
    @Valid Q q;                              // @Valid cascades to nested DTOs
}
```
- **DO:** annotate with `jakarta.validation` constraints; add `@Valid` for nested
  cascade.

---

## 8. Authentication / context

Built-in auth **verifies** an incoming JWT against a remote **JWKS** endpoint (the
`uid()` path, once per authenticated request — `Es256Jwk.isValid`). krpc does **not
issue** tokens while serving RPCs: minting the ES256 JWT and publishing the matching
EC public JWKS at `rpc.server.jwks` is your **login service's** job (krpc ships
`Es256Jws`/`Es256Signature` for the signing side if you want it). The recipe below is
the standard "require login" (verify) side — follow it verbatim.

### 8.1 Require login on a service

Credential precedence: method `@RequireCredential` > class `requireCredential` >
default false (`UnaryMethod.java:59-65`). When required, `ctx.checkCredential()`
runs before the method and throws (hard) on failure (`UnaryMethod.java:197-199,227-229`).

```java
@UnsafeWeb(requireCredential = true)     // JWT required for every method
public interface AccountService { ... }

// or per-method:
@UnsafeWeb
public interface AccountService {
    @UnsafeWeb.RequireCredential          // JWT required for this method only
    AccountInfo me();
}
```

### 8.2 Configure JWKS

```properties
rpc.server.jwks=<your-jwks-base-url>
```
Read by both `rpc-server-spring` and `rpc-server-quarkus`
(`InitJwsVerify.java:27,30`). If the URL does not end in `.json`, krpc appends
`.well-known/jwks.json` automatically (`JwsVerify.java:32,62-64`) — so configure the
base URL, not the full document path. No JWKS set → auth check is skipped entirely
(`InitJwsVerify.java:56-58` / spring `:51-54`).

### 8.3 ES256 / EC keys only (silent-failure trap)

`loadJwks` loads **only keys with `kty == "EC"`** and ignores everything else with
**no error** (`JwsVerify.java:90-95`). RS256/RSA keys in the JWKS are silently
dropped; tokens signed with them later fail with `kid not found`
(`JwsVerify.java:127-129`). Verification is hard-wired to `SHA256withECDSA` /
`ES256` (`Es256Jwk.java:35,37,98-106`).

- **DON'T:** publish an RSA/RS256 JWKS and expect it to work — it fails silently at
  load, loudly at verify.
- **DO:** sign tokens with ES256 and publish EC (P-256/384/521) keys
  (`Es256Jwk.java:32-34`).

### 8.4 Token extraction

`Authorization: Bearer <jwt>` is tried first; if absent, the cookie named
`access-token` (`JwsVerify.DEFAULT_COOKIE_NAME`, `JwsVerify.java:33`;
extraction `CredentialVerify.java:34-59`; selection `ServerContext.java:125-131`).
The cookie name is configurable via `rpc.server.jwsCookie`
(`InitJwsVerify.java:31,42` quarkus / `:28,39` spring).

### 8.5 Reading the user in an impl

```java
public AccountInfo me() {
    String userId = ServerContext.current().uid();   // JWT sub
    return load(userId);
}
```

- `ctx.uid()` — returns the JWT `sub`; throws (NPE) if not authenticated, so use
  only on `requireCredential` endpoints (`ServerContext.java:161-163`).
- `ctx.softUid()` — returns the `sub` or `null` when not logged in; never throws on
  missing/invalid token (`ServerContext.java:142-155`).

### 8.6 Key rotation and revocation

An unknown `kid` triggers a JWKS refetch on a short **30 s** backoff
(`MIN_FETCH_GAP_MILL`), so a freshly rotated-in key is picked up quickly. On a cache
**hit**, JWKS is refreshed in the background at most once per **5 min**
(`GAP_MILL`) — and each successful fetch **rebuilds** the keyset (replace, not
merge), so a key **removed** from the published JWKS stops verifying within that
serving and does **not** burn the 5-min window (`JwsVerify.java`, O3/O4). A successful
fetch that returns **zero usable keys** (`{"keys":[]}`, null keys, or only non-EC
entries) on an already-ready verifier is treated as **full revocation** — the live
keyset is replaced with an empty map so every `kid` misses and is rejected
`PERMISSION_DENIED` (fail-closed), rather than the empty result being dropped and the
stale keyset kept alive (HARDEN-B1 fix-round-1, O3). Before the first successful load,
an empty/null-keys document instead keeps the verifier not-ready → `UNAVAILABLE`.

### 8.7 Production hardening — fail-closed by default

krpc is **fail-closed**: if the JWKS URL is unreachable or invalid, authentication is
**never silently disabled**.

- **Default (`exitOnJwksError=false`):** the verifier is registered but **fail-closed**.
  Until JWKS loads successfully, every credential-required request is rejected with
  gRPC **`UNAVAILABLE`** (`"JWKS not ready"` — distinct from a bad token's
  `UNAUTHENTICATED`/`PERMISSION_DENIED`, so ops can tell "auth backend down" from
  "bad credential"). A background daemon retries the fetch with gentle backoff
  (5 s → 60 s); when it succeeds, auth goes live automatically (logged at WARN).
- **`exitOnJwksError=true`:** startup **aborts loudly** instead of coming up
  fail-closed. Use when your deployment model would rather crash-loop than serve
  while the auth trust root is unreachable.

```properties
# optional: abort startup instead of coming up fail-closed
rpc.server.exitOnJwksError=true
```

> **Behaviour change (HARDEN-B1):** pre-1.0.4 the default **fail-open** — a bad/unreachable
> JWKS at boot logged a warning and left auth **off** until restart. That path is gone;
> there is no configuration that yields silent fail-open. A JWKS fetch is also bounded
> (5 s connect / 10 s request timeout, 1 MiB body cap) against a slow/oversized IdP.

### 8.8 Claim validation (exp / nbf / aud)

- **`exp`** is required and enforced — a token past `exp` is rejected
  `UNAUTHENTICATED`; a token **missing** `exp` is malformed → `UNAUTHENTICATED`.
- **`nbf`** is enforced when present, with a 60 s clock-skew allowance — a token whose
  `nbf` is more than 60 s in the future is rejected `UNAUTHENTICATED` (HARDEN-B1,
  behaviour change; tokens without `nbf` are unaffected).
- **`aud`** validation is **opt-in, default OFF** (behaviour unchanged for single-`aud`
  deployments). Per RFC 7519 the token's `aud` may be a single string **or** an array;
  both are accepted (a single-string `aud` no longer errors). Set a comma-separated
  allow-list to enable:

```properties
# optional: reject tokens whose aud does not intersect this list. empty = off.
rpc.server.jwsAudiences=api-gateway,internal
```

- Malformed tokens (bad structure / base64 / JSON / missing `exp` / missing `kid`) and
  non-canonical signatures (not exactly 64 raw bytes, incl. bare ASN.1/DER and
  degenerate all-zero-R / all-zero-S concat forms) are rejected `UNAUTHENTICATED` and
  logged at DEBUG only (no per-token stack-trace flood). No malformed/empty/failed path
  escapes as gRPC `UNKNOWN` (HARDEN-B1 fix-round-1).

---

## 9. Other authoring annotations

- **`@Doc`** (`Doc.java`) — TYPE/FIELD/METHOD docs fed into generated clients;
  `hidden=true` omits from clients; nested `@ErrorCode` documents business codes.
- **`@Cached`** (`Cached.java`) — client-side cache by `expireSeconds` (method >
  `@RpcService.expireSeconds` > manager default); **only `code==OK` results are
  cached** (`MethodCallProxyHandler.java:148`).

---

## 10. Service implementation

Implement the interface (optionally via an `AbstractXxxService` base for shared
defaults). Container wiring:

- **Quarkus:** `@ApplicationScoped @Startup` (`DemoServiceImpl.java:19-22`).
- **Spring:** see `rpc-server-spring`.

The gRPC/Netty server executor runs on **virtual threads** (one named virtual
thread per RPC, `rpc-server/.../exe/ThreadPool.java`; JDK 21 / ADR-0002) —
handlers may block on IO freely; don't add your own bounded RPC thread pool.

---

## 11. Wire / JSON / tracing (mostly automatic)

- Wire envelope `OutputProto` uses single-letter fields `c`(code)/`m`(msg)/`bs`(bytes);
  null `msg`/`data` are not written (`ServerResult.java:30-38`).
- Trace propagation is automatic and uses **W3C Trace Context** (ADR-0003):
  the server reads the inbound `traceparent` header into MDC (parsing
  `traceId`/`spanId` for the log pattern) and carries `tracestate` + `x-request-id`;
  the client forwards `traceparent` opaquely on outbound calls
  (`TraceMeta.java`, `ServerContext.java`, `MethodCallProxyHandler.java`,
  `PropagateTraceCall.java`). The framework propagates context, it does not start
  spans. B3 (`x-b3-*`) is no longer emitted or read — a wire change vs 1.0.0;
  sibling clients must adopt W3C for cross-service trace continuity.

---

## 12. Build, test, release

```bash
./gradlew clean build -x test    # -x test: integration tests need an internal
                                 # MySQL host absent in clean envs and will hang
./gradlew allDeps
```
JDK 21 baseline; pinned Gradle wrapper 8.14.5. When you skip tests, say so and
list what was not validated.

Native (container build, Mandrel/JDK 25):
```bash
./gradlew :test-server:build -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  -Dquarkus.package.jar.enabled=false -x test
```

Release to Maven Central (group `tech.krpc`; credentials in
`~/.gradle/gradle.properties`, never in the repo):
```bash
GRADLE_CMD=./gradlew gradle/publish-central.sh upload        # build+sign+upload, wait VALIDATED (reversible)
GRADLE_CMD=./gradlew gradle/publish-central.sh publish --yes  # IRREVERSIBLE — cannot be deleted/overwritten
```
Set `version` + `changelog.md` first; `ext-rpc-gen` (group `tech.krpc.ext`) is
released on its own cycle, not in the krpc bundle. Tag `vX.Y.Z` and cut the
GitHub release after Central publish succeeds.

### 12.1 Server limits / hardening config

```properties
# Max concurrent in-flight calls per HTTP/2 connection. Default 2000; 0 = unlimited.
rpc.server.maxConcurrentCallsPerConnection=2000
```
App-layer defence-in-depth against HTTP/2 concurrent-stream flooding
(CVE-2026-47244), complementing the transport-layer Netty 1.0.3 bump. Read by
`rpc-server-quarkus` (`RpcServiceExpose`, env `RPC_SERVER_MAXCONCURRENTCALLSPERCONNECTION`
via SmallRye's default mapping) and `rpc-server-spring` (`RpcServiceExposer`, relaxed
binding — `rpc.server.max-concurrent-calls-per-connection` / env also work); applied on
the Netty gRPC `ServerBuilder` in `RpcServerBuilder.init()`. The value is advertised to
clients as HTTP/2 `SETTINGS_MAX_CONCURRENT_STREAMS`, so a client exceeding it on a
**single channel** is **back-pressure queued** — excess streams wait client-side until
capacity frees, they are **not** failed. `0` (the only unlimited value) restores the
pre-1.0.4 behaviour; a **negative value fails fast** as a config error. Non-Netty gRPC
providers (none ship by default) ignore the cap with a warning rather than failing.

### 12.2 MCP bridge (agent tools over `POST /mcp`)

ADR-0004 P1: a hand-written [Model Context Protocol](https://modelcontextprotocol.io)
bridge (spec `2025-06-18`, JSON-RPC 2.0 over Streamable HTTP), on the same netty
HTTP host as `/agent/*` (`http.port`, default `8080`). No third-party MCP SDK; no
new module or Central artifact.

```properties
# Default OFF = byte-level zero new surface (the /mcp path is not even registered).
rpc.server.mcp.enabled=true
```
Env: `KRPC_MCP=true` (also honoured directly) or the SmallRye mapping
`RPC_SERVER_MCP_ENABLED`. Read by `rpc-server-quarkus` (`McpHandler`).

- **Tools = the `@UnsafeWeb(agentTool=true)` subset only.** `agentTool` (TYPE-level,
  default `false`) is a **deliberate subset of web exposure** — `@UnsafeWeb` alone
  does **not** create a tool. `/agent/discover` is unaffected (its web-filtered view
  is unchanged); the two surfaces are distinct. ON with no `agentTool` method = an
  empty `tools` list (valid).
- **Tool name** = `Service_method` (underscore-joined; matches the client-enforced
  `^[a-zA-Z0-9_-]+$`). `inputSchema`/`outputSchema` are JSON Schema derived from the
  DTO type tree + jakarta constraints (`@NotBlank`→`required`+`minLength`, `@Size`,
  `@Min`/`@Max`, `@Pattern`, `@Email`) + `@Doc`; `outputSchema` is the
  `RpcResult<T>`-unwrapped `T`.
- **`tools/call` runs the identical dispatch as `/agent/invoke`** (`WebInvoker.invokeWeb`):
  the credential check is **not bypassed**, and only agentTool methods resolve
  (unknown/non-agentTool/hidden → JSON-RPC `-32602`). Success → `content` text +
  `structuredContent` (unwrapped `data`); a non-zero `RpcResult.code` or a thrown
  credential/system error → `isError:true`.
- **Methods**: `initialize`, `notifications/initialized` (→ HTTP 202), `tools/list`,
  `tools/call`, `ping`. Transport is JSON-response mode only (one JSON object per
  POST); SSE is spec-optional and not used (krpc tools are unary). Per spec 2025-06-18:
  `GET /mcp` → `405 Method Not Allowed` (`Allow: POST`, no SSE stream offered here);
  an `MCP-Protocol-Version` header the server does not support → `400`
  (`initialize` is exempt — it negotiates via the body). Auth/rate-limit remain the
  gateway's responsibility, same as the P0 agent surface.
- **Verified** with real MCP clients over Streamable HTTP — `initialize` captured via a
  `@modelcontextprotocol/sdk` client script (the Inspector CLI does not print the raw
  result), `tools/list` + `tools/call` via the official `@modelcontextprotocol/inspector`
  CLI — on JVM **and** GraalVM native (Mandrel 25/JDK25); `initialize` +
  `tools/list` byte-identical across both, `tools/call` differs only in the runtime
  timestamp.
  Full verbatim transcripts (command lines + complete output):
  [`docs/mcp-transcripts/jvm.txt`](https://github.com/martin1847/krpc/blob/f7c8e70/docs/mcp-transcripts/jvm.txt)
  and [`docs/mcp-transcripts/native.txt`](https://github.com/martin1847/krpc/blob/f7c8e70/docs/mcp-transcripts/native.txt)
  (native includes the boot log). OFF path (`/mcp` absent, 404) is covered by
  `McpDisabledQuarkusTest`, not by the transcripts.

---

## 13. Native image (GraalVM)

Single source of truth for building krpc services as native images — humans and
agents read the same section. Consumer-proven 2026-06 on 8 downstream Quarkus
3.33.2 services (boot-to-ready 0.028s native). Items tagged `[gap → NATIVE-00x]`
are framework defects with roadmap items: apply the workaround now, delete it
when the item completes.

### 13.1 io.grpc version alignment — Quarkus LTS support matrix

krpc tracks the Quarkus LTS BOM's io.grpc version (Option A, ADR NATIVE-001).
`gradle.properties` pins `grpcVersion` to what the supported Quarkus LTS ships, so
a Quarkus consumer's highest-wins resolution converges on a single io.grpc — no
consumer-side force, and native-image's `Target_io_grpc_ServiceProviders`
substitution matches the class shape.

| krpc release                    | Quarkus LTS | io.grpc | consumer force |
|---------------------------------|-------------|---------|----------------|
| ≥ 1.0.3 (this alignment)        | 3.33.x LTS  | 1.79.0  | none — aligned |
| published ≤ 1.0.2               | 3.33.x LTS  | 1.82.0  | required (below) |

(Bump `grpcVersion` in lockstep when adopting the next Quarkus LTS.)

**For published krpc ≤1.0.2 only** (ships io.grpc 1.82.0, skewed above the BOM → native-image
aborts during Initializing): force `io.grpc:*` back to the BOM version in the root
build — `configurations.all { resolutionStrategy.eachDependency { if (it.requested.group == 'io.grpc') it.useVersion '1.79.0' } }`.

### 13.2 Server-side native support — use `ext-rpc` ≥ 1.0.2 (NATIVE-002, shipped)

Since `tech.krpc.ext:ext-rpc` **1.0.2**, the Quarkus extension registers everything a
native SERVER needs with zero per-service glue: the deployment processor emits
reflective registration for the grpc provider impls and a build-time GraalVM
`Feature` that bakes `io.grpc.ServerProvider.provider()` into the image heap
(krpc itself only covers the client side —
`rpc-client/.../ext/GraalvmBuild.java:14-18`). It also gates its grpc-netty
substitutions on `quarkus-grpc-common` absence, so they no longer collide with
Quarkus's own.

**On `ext-rpc` ≤ 1.0.1 only**, a native server dies at boot with
`ManagedChannelProvider$ProviderNotFoundException: No functional server found`
and duplicate-substitution aborts; either upgrade, or apply the legacy trio
(per-service `ServerProvider` Feature + `@RegisterForReflection` provider holder +
`quarkus.class-loading.removed-resources` strip) — recipe preserved in the
`v1.0.3` tag of this file and in `ext-rpc` PR #2.

### 13.3 Build recipe and known runtime issues

- Build command: [§12](#12-build-test-release). Native needs
  `-Dquarkus.package.jar.enabled=false` (Gradle can't output both), and the
  builder image must match your Quarkus/JDK line — for the JDK 21 baseline use
  `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21` (krpc's own
  test-server was additionally validated on Mandrel 25 / jdk-25).
- Static-heap violations are per-service: any `static final` SecureRandom /
  Random / network-touching singleton fails analysis; fix with
  `--initialize-at-run-time=<class>` (the native build error names the class).
- Known issue: a native runner **SIGSEGVs at startup when datasource env/config
  is absent** (logs "started", then exit 139). Ensure `QUARKUS_DATASOURCE_*` is
  set; JVM mode fails gracefully, native does not.

### 13.4 io_uring transport (evaluated 2026-07)

Status: evaluated. A flag-gated PoC exists on eval branch `feat/iouring-eval` —
**not shipped; the default transport stays NIO in native / epoll-or-NIO on JVM.**

- **Netty artifact constraint.** Today's stack (Quarkus 3.33 LTS = Netty 4.1) can
  only use the ARCHIVED incubator artifact
  (`io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.26.Final`).
  The graduated transport (`io.netty.channel.uring`) is Netty-4.2-only, which
  arrives with Quarkus 4 / Vert.x 5.
- **Native-image: works.** Flag `KRPC_IOURING`, hand-authored JNI/reflect/resource
  metadata, `--initialize-at-run-time`; +0.56 MiB image, +1.9 MiB RSS.
- **Benchmark verdict (aarch64, containerized): 5–6% SLOWER than NIO** on krpc's
  typical small-message unary path. io_uring's win case (many connections,
  syscall-bound) is not this profile. Details:
  workspace `docs/orchestration/IOURING-001_{RESEARCH,BENCH}_omp.md`.
- **Ops note.** Docker's default seccomp profile blocks io_uring syscalls
  (`io_uring_setup` → EPERM); running the flag ON in containers needs an allowing
  seccomp profile.

### 13.5 Reflection coverage (what the framework registers for you)

Native is closed-world: the framework registers reflection at build time, but
only for what its build-time scan can reach. Know what is and is not covered.

- **The extensions must be on the native build.** DTO reflection is registered by
  the `ext-rpc` Quarkus deployment processor: it indexes every `@RpcService`
  interface (Jandex), walks each method's param + return types, and emits
  `ReflectiveClassBuildItem` for the DTOs with `methods(true).fields(true)`
  (`ext-rpc/ext-rpc-deployment/.../RpcProcessor.java:75-94,148-150`). `ext-mybatis`
  does the equivalent for its mapper/entity types. Drop the extension and **nobody**
  registers your DTOs — they reflect fine on JVM but fail at native runtime.
- **Nested DTOs are auto-covered only 8 levels deep.** After the top-level DTOs, the
  processor recurses through nested field types up to `max_level = 8`
  (`RpcProcessor.java:158-170`); beyond that it logs `TOO DEEP Nest Dto`
  (`:180`) and stops registering. Keep DTO nesting shallow, or register deeper types
  by hand.
- **Third-party bean types are not scanned.** The recursion explicitly skips `java.*`
  types (`RpcProcessor.java:225`) and only follows types reachable from your own
  DTO fields — an external-library class referenced by a DTO is outside the scan and
  will throw at native runtime. Register it yourself with Quarkus's
  `@RegisterForReflection` (`io.quarkus.runtime.annotations.RegisterForReflection`),
  e.g. on an aggregate class:

  ```java
  import io.quarkus.runtime.annotations.RegisterForReflection;

  @RegisterForReflection(targets = { com.vendor.lib.Foo.class, com.vendor.lib.Bar.class })
  public final class NativeReflectionConfig {}
  ```

  Or add the class to a `META-INF/native-image/<group>/reflection-config.json`.
- **Framework classes are already registered — don't re-register them.** krpc's own
  runtime types ship reflection metadata in each module's
  `META-INF/native-image/*/reflection-config.json` + `native-image.properties`
  (`rpc-api/...`, `rpc-common/...`, `rpc-client/...`,
  `rpc-server-quarkus/src/main/resources/META-INF/native-image/rpc-server/...`).
  You only own the third-party types your DTOs pull in.

### 13.6 Native checklist for a consumer service

- [ ] **krpc ≤1.0.2 only:** `io.grpc:*` forced to the Quarkus BOM version (root build) — §13.1. (krpc >1.0.2 is aligned; skip.)
- [ ] `ext-rpc` ≥ 1.0.2 on the build (server-side native support; ≤1.0.1 needs the legacy trio) — §13.2.
- [ ] Builder image matches Quarkus/JDK line; `package.jar.enabled=false` — §13.3.
- [ ] Per-service `--initialize-at-run-time` for static-heap violations — §13.3.
- [ ] Datasource config present at runtime (SIGSEGV otherwise) — §13.3.
- [ ] `ext-rpc` / `ext-mybatis` extensions on the build (DTO reflection) — §13.5.

---

## 14. Quick checklist for a new service

- [ ] Interface annotated `@RpcService`; name has no dots.
- [ ] Every method `RpcResult<Dto> m(OneDto)` or `m()` — never 2+ params, never raw return.
- [ ] DTO scalar fields are boxed types; no `Map`/`enum` in responses.
- [ ] Business failures → `RpcResult.error(code>0, msg)` with hundreds/thousands codes; system/security → throw.
- [ ] `@UnsafeWeb` only if frontend-facing; `requireCredential` where auth is needed.
- [ ] `jakarta.validation` constraints (+ `@Valid` for nesting) where input must be checked.
- [ ] `ctx.uid()` / `ctx.softUid()` for the user; don't hand-roll trace propagation.

---

**Source of truth:** ADRs (`docs/decisions/`) > module docs (`docs/modules/`,
`FOR`/`NOT FOR`) > module evolution > roadmap (`docs/roadmap/active-roadmap.md`)
> inline TODOs. Index: `docs/INDEX.md`. Governance: `AGENTS.md`.
