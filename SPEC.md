# KRPC Development Spec

<!-- CANONICAL. This root SPEC.md is the source of truth. A byte-identical copy is
     bundled at skills/krpc/references/SPEC.md so the `krpc` agent skill stays
     self-contained when copied into a consumer project — edit THIS file; CI
     (.github/workflows/skill-sync.yml) fails if the mirror drifts. -->

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

A method violating either is **rejected fail-fast at discovery** (HARDEN-B3, C9/AUD-omp-09): an
*abstract* `@RpcService` method with an illegal signature throws `IllegalStateException` from
`RefUtils.toRpcMethods` (server startup / client stub init / codegen). Pre-HARDEN-B3 it was
**silently dropped** (unregistered, failed to resolve at runtime — the single most common mistake).
`default`/`static` methods (and Object redeclarations) are exempt — the sanctioned escape hatch for
interface helpers.

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
return RpcResult.ok(data);          // throws NPE if data == null
return RpcResult.error(666, "...");  // throws IllegalArgumentException if code <= 0, NPE if msg == null
result.isOk();                       // code == 0
result.ifOk(fn);                     // maps OK data → a NEW RpcResult (never mutates the receiver)
result.orElseThrow();
```

> HARDEN-B3 (C3/AUD-omp-28): `ok`/`error` now enforce these invariants at runtime (they were
> `assert`-only — no-ops under the default `-da` JVM). `ifOk` builds a **new** result instead of
> re-typing + mutating `this` (an aliasing bug that swapped a shared caller's DTO). The no-arg
> `error()` reinterpret-as-failure throws `IllegalStateException` if called on an OK result.

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

### Collection fields — use `List<T>`, not arrays
- Use **`List<T>`** for a sequence field; do **not** use Java arrays. Arrays are
  **UNSUPPORTED**: the contract meta does not model array component types, so generation
  would reference an undeclared type. The meta scan **fails fast** on an object-array field
  (`Zebra[]`, `String[]`), naming the declaring DTO + field (`RpcMetaServiceImpl.checkFieldContract`).
- **Exemption:** a **single-dimension** array of a *primitive* (`byte[]`, `int[]`) is allowed
  — the binary/scalar-payload convention (`test-api/.../dto/Img.java:30`). **Multi-dimensional**
  arrays (`int[][]`) and object arrays are UNSUPPORTED — use `List<T>` / `List<List<T>>`.
- **`Map<K,V>` is NOT RECOMMENDED** — generated client code loses readability; model the shape
  as an explicit DTO class. Not an error: the scan logs a WARN (deduped per DTO field).

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

Built-in auth **verifies** an incoming JWT against a remote **JWKS** endpoint (the `uid()` path,
once per authenticated request — `Es256Jwk.isValid`). krpc does **not issue** tokens while serving
RPCs: minting the ES256 JWT + publishing the matching EC JWKS at `rpc.server.jwks` is your **login
service's** job (krpc ships `Es256Jws`/`Es256Signature` for the signing side). Follow the "require
login" (verify) recipe below verbatim.

### 8.1 Require login on a service

Credential precedence: method `@RequireCredential` > class `requireCredential` >
default false (`UnaryMethod.java:59-65`). When required, `ctx.checkCredential()`
runs before the method and throws (hard) on failure (`UnaryMethod.java:197-199,227-229`).

```java
@UnsafeWeb(requireCredential = true)  // JWT required for every method
public interface AccountService { ... }
// per-method instead:  @UnsafeWeb interface … { @UnsafeWeb.RequireCredential AccountInfo me(); }
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

An unknown `kid` triggers a JWKS refetch on a **30 s** backoff (`MIN_FETCH_GAP_MILL`), so a
rotated-in key is picked up quickly; on a cache **hit** JWKS refreshes in the background at most
once per **5 min** (`GAP_MILL`). Each successful fetch **rebuilds** the keyset (replace, not
merge), so a **removed** key stops verifying within that serving without burning the 5-min window
(`JwsVerify.java`, O3/O4). A successful fetch returning **zero usable keys** (`{"keys":[]}`, null,
or only non-EC) on an already-ready verifier = **full revocation**: the live keyset is replaced
empty so every `kid` is rejected `PERMISSION_DENIED` (fail-closed), not dropped keeping the stale
keyset (HARDEN-B1 fix-round-1, O3). Before the first successful load, an empty/null-keys document
keeps the verifier not-ready → `UNAVAILABLE`.

### 8.7 Production hardening — fail-closed by default

krpc is **fail-closed**: an unreachable/invalid JWKS URL never silently disables auth.

- **Default (`exitOnJwksError=false`):** verifier registered but **fail-closed** — until JWKS
  loads, every credential-required request is rejected gRPC **`UNAVAILABLE`** (`"JWKS not ready"`,
  distinct from a bad token's `UNAUTHENTICATED`/`PERMISSION_DENIED` so ops can tell "auth backend
  down" from "bad credential"). A background daemon retries with backoff (5 s → 60 s); on success
  auth goes live automatically (WARN).
- **`exitOnJwksError=true`:** startup **aborts loudly** instead — for deployments that prefer
  crash-loop over serving while the auth trust root is unreachable.

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

- Wire envelope `OutputProto` uses single-letter fields `c`(code)/`m`(msg)/`bs`(bytes); null
  `msg`/`data` are not written (`ServerResult.java:30-38`).
- Trace propagation is automatic, **W3C Trace Context** (ADR-0003): the server reads inbound
  `traceparent` into MDC (`traceId`/`spanId` for the log pattern), carries `tracestate` +
  `x-request-id`; the client forwards `traceparent` opaquely (`TraceMeta.java`,
  `ServerContext.java`, `MethodCallProxyHandler.java`, `PropagateTraceCall.java`). It propagates
  context, does not start spans. B3 (`x-b3-*`) is no longer emitted/read — a wire change vs 1.0.0;
  sibling clients must adopt W3C for cross-service continuity.

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

ADR-0004 P1: a hand-written [Model Context Protocol](https://modelcontextprotocol.io) bridge
(spec `2025-06-18`, JSON-RPC 2.0 over Streamable HTTP) on the same netty HTTP host as
`/agent/*` (`http.port`, default `8080`). No third-party SDK, no new module/artifact.
**Default OFF** (the `/mcp` path is not even registered); tools = the
`@UnsafeWeb(agentTool=true)` subset only (`@UnsafeWeb` alone does **not** create a tool).
`tools/call` runs the identical dispatch as `/agent/invoke` — credential check **not**
bypassed. Full behavior (JSON-Schema derivation, methods, dispatch/error mapping, GET→405,
verification transcripts) → `skills/krpc/references/mcp-bridge.md`.

```properties
# Default OFF = byte-level zero new surface (the /mcp path is not even registered).
rpc.server.mcp.enabled=true
```
Env: `KRPC_MCP=true` or the SmallRye mapping `RPC_SERVER_MCP_ENABLED` (`rpc-server-quarkus`,
`McpHandler`).

### 12.3 Client limits / hardening config

```properties
# Default outbound call deadline (ms) when the caller sets none. Default 30000; 0 = unlimited.
rpc.client.defaultDeadlineMillis=30000
```
**⚠️ Behaviour change (HARDEN-B2, O2):** a deadline-less outbound call previously used
`CallOptions.DEFAULT` (no deadline) so a hung upstream blocked the calling virtual thread forever;
now it gets this default via one authority (`ClientDeadline.apply`, `rpc-client`) shared by the
proxy path (sync + async, `MethodCallProxyHandler`), `GeneralizeClient`, and rpcurl (Spring binds
it in `RpcClientAutoConfigure.afterPropertiesSet` — the **single** point; a future Quarkus client
binds the same authority so the two can't drift, mirroring the Batch-1
`JwsVerify.bootstrapAndRegister` dedup). **A legitimate slow call is cut with
`DEADLINE_EXCEEDED`** — raise the value or set an explicit per-call deadline (which always wins:
`ClientContext.withCallOptions(CallOptions.DEFAULT.withDeadlineAfter(...), ...)` or a filter
setting `CallOptions.getDeadline()`). `0`/negative = unlimited = pre-1.0.4.

Other client fixes this batch (no config): the default `@Cached` `SimpleLRUCache` is now
thread-safe (`get`/`set` synchronised — `accessOrder` re-links on `get`, corrupting concurrent
reads under VTs); the cache key tags input `dataCase` so distinct `byte[]` params stop colliding
(C2); cached `byte[]` is cloned on both edges so a caller mutation can't poison the entry (O10);
and the Spring `RpcClientFactory` is a `destroyMethod="close"` bean (was leaked every refresh —
channel + gRPC executor threads), with a portless config URL falling back to the protocol default
port (C8) and a graceful bounded-await `shutdown()` (C6-client).

### 12.4 HTTP face — error model, threading & idle limits (HARDEN-B4)

The `http-server` netty host serving `/agent/*` and `/mcp` (`AbstractHttpHandler`, `HttpServer`).

**Error model (C7 / AUD-omp-20 / AUD-omp-21).** Every error is a uniform JSON envelope
`{"code":<httpStatus>,"message":<text>}` with matching `content-type: application/json` (was:
everything → 500 with the raw exception message on the wire):

| condition | status |
|---|---|
| malformed JSON body | `400` (neutral `"malformed JSON…"`; Jackson internals only in the log) |
| empty body to a validating endpoint | `400` `"request body is required"` |
| bean-validation failure | `400` (field path + constraint; **never** the rejected value — PII stays in the log) |
| request body > 1 MiB | `413` (netty `HttpObjectAggregator`) |
| unknown path | `404` |
| handler internal error | `500` (status reason phrase only — **never** `ex.getMessage()`) |

A malformed body is a 400 JSON with the connection kept open (no bare `ctx.close()` reset;
AUD-omp-20) — the HTTP analogue of the gRPC `JsonDecodeException`→`INVALID_ARGUMENT` mapping
(HARDEN-B3); the agent/MCP surface never discloses an internal reason.

**Threading (O6 / AUD-omp-09).** `channelRead0` dispatches `handler.handle()` to a per-request
**virtual thread** (`Executors.newVirtualThreadPerTaskExecutor()`) so blocking never runs on the
netty NIO eventLoop (which would starve the bounded workerGroup); the response write is scheduled
back on the channel's eventLoop. Backpressure: `setAutoRead(false)` in flight bounds blocking work
to ≤1 per connection, re-armed (`setAutoRead(true)`) when the queue drains.

**Per-connection response ordering (O6 fix-round-1).** HTTP/1.1 needs in-order responses; since
`handle()` runs off the eventLoop, two requests pipelined in one TCP segment could otherwise race
and frame a response against the wrong request. Each connection carries a **FIFO queue**
(`ConnState`, a netty channel attribute touched only on the eventLoop): every request (handler and
error/404 alike) is enqueued, one served at a time, next dispatched only after the current response
is written — strict one-in/one-out.

**Idle / slow-loris (AUD-omp-52).** An `IdleStateHandler` (reader-idle `HttpServer.READ_IDLE_SECONDS`
= 60s) closes a connection sending no inbound bytes in that window. Independently, `HttpServer.start()`
shuts down both `NioEventLoopGroup`s if bind fails (was: leaked NIO threads on retry loops).

### 12.5 Deployment environment tag — `APP_ENV`

`APP_ENV` names the **deployment environment** for display and telemetry only
(`rpc-common/.../util/EnvUtils.java`). The parse is case-insensitive and
whitespace-trimmed:

| value | aliases | `AppEnv` |
|---|---|---|
| `dev` | `develop`, `development` | `DEV` |
| `test` | — | `TEST` |
| `staging` | `stage`, `pre` | `STAGING` |
| `prod` | `production` | `PROD` |

- **Unset → `DEV`** (local-dev default; only consumer today is the startup banner). **Unknown →
  warn once + `PROD`** (fail-closed, never throws — before this an IAC `stage`/`pre` crashed
  startup via `AppEnv.valueOf`).
- **Display / telemetry only — never a behaviour switch.** A behavioural toggle gets its **own**
  env flag (template `KRPC_MCP` / `rpc.server.mcp.enabled`, §12.2); if a branch is ever keyed off
  the environment, the unset default flips `DEV`→`PROD`.
- **Two axes:** the `APP_ENV` **label** is independent of the **Quarkus runtime profile**
  (`dev`/`test`/`prod`) — **staging runs the `prod` profile** while carrying `APP_ENV=staging`;
  don't wire them to the same value.
- **Telemetry:** emit as OTel resource attr `deployment.environment.name`, **lowercase**
  (`dev`/`test`/`staging`/`prod`).

### 12.6 Persistence & transaction discipline

The **ecosystem default is weak transaction, throughput first**, implemented
*safe-by-construction* by every data-access extension. Under the VT runtime (one VT per RPC —
[§10](#10-service-implementation)) the pooled connection is the scarce resource; most RPC methods
are reads or single-statement writes needing no cross-statement atomicity, so the default borrows
a connection per operation and returns it **immediately** (never parked on a request/session/
thread) — no tuning flag, you write nothing. A multi-write invariant opts in explicitly to an
`@Transactional`/JTA scope, binding the connection until it ends (the author's conscious trade).

| your method does | posture |
| --- | --- |
| a read, or one single-statement write | default auto-commit — do nothing |
| **≥ 2 writes that must all commit or all roll back** | explicit `@Transactional` / JTA |

- **DON'T:** ship a **silent multi-write** with no transaction — each statement commits on its
  own, so a mid-sequence failure leaves earlier writes durably applied (partial state, no rollback).
- **DON'T:** cache a borrowed connection / `SqlSession` in a field or `ThreadLocal` — that pins the
  scarce resource and defeats the return-immediately default (the leak class this posture prevents).

The data-access extensions (`ext-mybatis` ≥ 1.0.2) are safe-by-construction here (the default path
cannot leak or pin a connection); per-extension config is in each extension's README, rationale =
ecosystem **ADR-0002 (weak-transaction default)**.

---

## 13. Native image (GraalVM)

Native-image builds are consumer-proven (2026-06, 8 downstream Quarkus 3.33.2 services,
boot-to-ready 0.028s native). **Full recipes — io.grpc/Quarkus-LTS version matrix, `ext-rpc`
server-side support, io_uring eval, reflection coverage → `skills/krpc/references/native-image.md`**
(`[gap → NATIVE-00x]` = framework defects to delete when the roadmap item lands; the `§13.x`
refs below point into that file). Day-1 checklist for a consumer service:

- [ ] **krpc ≤1.0.2 only:** `io.grpc:*` forced to the Quarkus BOM version (root build) — §13.1. (krpc >1.0.2 aligned; skip.)
- [ ] `ext-rpc` ≥ 1.0.2 on the build (server-side native; ≤1.0.1 needs the legacy trio) — §13.2.
- [ ] Builder image matches Quarkus/JDK line; `package.jar.enabled=false` — §13.3.
- [ ] Per-service `--initialize-at-run-time` for static-heap violations — §13.3.
- [ ] Datasource config present at runtime (SIGSEGV otherwise) — §13.3.
- [ ] `ext-rpc` / `ext-mybatis` extensions on the build (DTO reflection) — §13.5.

**Known issue — Caffeine + native reflection (field experience, consumer ecosystem 2026-07-19).**
A krpc native-image service using Caffeine's **bounded** cache path (a builder with bounded
features — `weakKeys`/`softValues`/`expireAfter*`/`maximumSize`/etc.) hits a dynamic
class resolution: Caffeine selects a pre-generated internal implementation class by its
*feature-encoded name* (names like `SSMSW`/`PSWMS`; the concrete class **varies** with the
feature combination). Green on the JVM, `ClassNotFoundException` at native runtime. Remedy:
register the concrete generated class(es) for reflection (`@RegisterForReflection(classNames
= …)` or reflect-config), and **re-verify whenever the feature combination changes** —
adding/removing features changes the encoded name (numeric capacity/duration values do not).
(Mechanism: method-handle-based dynamic class lookup in Caffeine 3.x, not `Class.forName`;
the exact class-name letters are field-reported, not pinned to a Caffeine version here.)

---

## 14. Contract evolution

The contract is the `*-api` module (`@RpcService` interfaces + DTOs; NS-1 — the interface
*is* the contract). Evolving it safely is publish-time discipline, not a runtime feature.

**On the wire, the field layout *is* the contract.** Polyglot clients do not key on any
version string — they encode the `InputProto`/`OutputProto` field numbers directly
(`InputProto{e=1,utf8=2,bs=3}`, `OutputProto{code=1, data oneof{msg=2,utf8=3,bs=4}}`;
`RpcConstants.VERSION` rides as `ApiMeta.sdkVersion` metadata only). This is why
years-old clients stay compatible across krpc build versions — and why those field
numbers are effectively frozen: reusing or renumbering one is a wire break regardless of
any version bump (verified across the Rust/TS/Python/Dart clients, 2026-07).

### 14.1 Version policy — the major stays `1`

`version` in `gradle.properties:5` (group `tech.krpc`); line `1.MINOR.PATCH`. Mirrors
`docs/support-policy.md` (superseded minor → security-only); today = **author discipline**,
not machine-enforced (§14.2).

| bump | contract change | old clients | deploy |
| --- | --- | --- | --- |
| **major** (`1`) | **frozen, never increments** | — | wire envelope stable (NS-2: wire-compat originates in krpc) |
| **minor** | **breaking** ("consumers must move") | may fail | front+back same window (§14.3) |
| **patch** | **compatible** (additive/fix) | keep working | stagger OK |

### 14.2 `*-api` publish gate — japicmp (recommended, NOT wired here)

> **External recommendation** — grep `japicmp` = 0 hits; a reference impl runs in a downstream
> consumer CI, not anchorable here. Manual REQUIRED step until krpc wires it.

Run a binary/source compat check (`japicmp`) of the candidate `*-api` jar vs the last release,
in the publish pipeline — "the interface is the contract" (NS-1) is real only once a machine
verifies the diff:

| candidate change | on patch bump | on minor bump |
| --- | --- | --- |
| incompatible (removed/renamed method, changed DTO field type, narrowed return) | **FAIL** | pass |
| additive-only (new method, new optional field) | pass | pass |

### 14.3 Deploy-window rule — breaking changes deploy together

> External recommendation (field report; not a krpc-code fact).

A **breaking** change (minor, §14.1) MUST deploy **front and back in the same window**;
additive/compatible (patch) MAY stagger. The wire envelope is frozen (major `1`, NS-2), so a
removed/renamed method or changed DTO shape has **no** compat bridge — cut over atomically.
(Real staging outage: a one-day skew put the two sides on different contracts, calls failed.)

---

## 15. Consumer guide (calling a KRPC service)

For agents/humans **calling** a running service (authoring is §1–§11).

> **Standing instruction — method names are contract, not guessable.** The rpcurl/gRPC
> path has no runtime schema handshake (external gap **RPCURL-001**; not in this repo's
> roadmap/ADRs); HTTP `/agent/discover` *does* introspect (§12.2 / `docs/agent-guide.md`).
> Until rpcurl introspection lands, **grep the `*-api` interface before calling** — a wrong
> method/field guess resolves to `UNIMPLEMENTED`/`code:5`, not a hint (§15.4).

### 15.1 Auth — two independent token lanes

A JWT rides one of **two lanes**, both feeding the same verifier (§8.4):
`Authorization: Bearer` is tried first, then cookie `access-token` (`JwsVerify.java:49`
`DEFAULT_COOKIE_NAME`; selection `ServerContext.java:137-144`).

| lane | wire | rpcurl | when |
| --- | --- | --- | --- |
| **Business** | `Cookie: access-token=<jwt>` | `-c "access-token=$TOK"` — value verbatim on `cookie`, use the `name=value` equals form (`rpcurl/.../RpcUrl.java:53-54,63-64`) | browser/frontend caller whose login set the cookie |
| **Framework** | `authorization: Bearer <jwt>` | `-t "$TOK"` — prepends `Bearer ` (`rpcurl/.../RpcUrl.java:56-57,67-68`) | s2s / tooling with the raw JWT |

- **No `KRPC_TOKEN` env var** — the framework lane is the `-t`/`--token` flag only.
- The agent HTTP surface (`/agent/invoke`, `/mcp`) forwards only `Authorization`, not `Cookie`
  → cookie lane unavailable there; send a bearer token (`docs/agent-guide.md` "Exposure model").

### 15.2 URL construction

Call path `{app}/{Service}/{method}` (§5); gateway URL `{gateway}/{app}/{Service}/{method}`,
each piece from `RefUtils.rpcServiceName` (`RefUtils.java:173-198`, derivation `:179-196`):
strip a leading `I`-before-uppercase and a trailing `Service`/`Rpc`, **no dots**.

| service declaration | published name | gateway path | reachable |
| --- | --- | --- | --- |
| `@UnsafeWeb IDemoService` | `Demo` (`I` stripped) | `{gateway}/{app}/Demo/{method}` | frontend + s2s |
| `@UnsafeWeb DemoService` | `Demo` (`Service` stripped) | `{gateway}/{app}/Demo/{method}` | frontend + s2s |
| `@UnsafeWeb FooRpc` | `Foo` (`Rpc` stripped) | `{gateway}/{app}/Foo/{method}` | frontend + s2s |
| `DemoService` (no `@UnsafeWeb`) | `-{app}/Demo` | *not web-served*; gRPC path `-{app}/Demo/{method}` (`rpcurl --no-web`) | **s2s only** |

For a non-`@UnsafeWeb` service `RefUtils` prepends `HIDDEN_SERVICE` (`-`) to the **whole**
`{app}/{Service}` → name `-{app}/{Service}`, gRPC method `-{app}/{Service}/{method}`
(`RefUtils.java:192-196`); `@UnsafeWeb` stays prefixless (the `-` keeps a hidden service off
the web gateway). CLI mirror: `rpcurl --no-web` prepends `-` to the app segment
(`rpcurl/.../RpcUrl.java:97-99`). On the plain-HTTP agent surface the name is used
**app-relative** (`{"service":"Demo","method":"..."}`), lookup = literal `"Service/method"`
key (`WebMethodRegistry.java:36-40`) — hidden/unknown → `null` → `code:5` NOT_FOUND
(`docs/agent-guide.md`).

### 15.3 Field-format conventions (quick table)

**DTO-authoring conventions** — how to *type* a field for clean polyglot round-trips, **not**
serializer behaviour (`JsonUtils.java` only guarantees `NON_NULL` output (`:28`) + lenient
input `FAIL_ON_UNKNOWN_PROPERTIES=false` (`:29`)). Combine with §4 (boxed scalars, `NON_NULL`).

| logical type | represent as | why (convention, not serializer-enforced) |
| --- | --- | --- |
| date | `String`, `YYYY-MM-DD` | `java.time` is NOT ISO-configured — `JsonUtils` adds `JavaTimeModule` only if on classpath (`JsonUtils.java:31-38`), never disables `WRITE_DATES_AS_TIMESTAMPS`, so a raw `LocalDate`/`OffsetDateTime` serializes numeric (test DTOs keep it off the wire, `test-api/.../dto/TimeResult.java:23-25` commented) |
| datetime | `String`, ISO-8601 with zone | same as date |
| money | `Long`, integer **cents** (`1999`=19.99) | avoids float rounding — never `Float`/`Double` (contrast tolerant geo `Float`, `Book.java:10-17`) |
| large id | `String` (`"7300000000000000001"`) | avoids the JSON/JS `2^53` cliff for 64-bit ids (`test-api/.../dto/Img.java:26` `String id`); a boxed `Long` risks silent precision loss in JS/TS/Dart |

### 15.4 Error-code behavior — how to branch

Branch on the failure *shape* (gRPC/rpcurl path):

| failure | code | carries | caller action |
| --- | --- | --- | --- |
| **Jakarta validation** | `INVALID_ARGUMENT` | field detail `Dto : field=value(constraint)` (`ValidatorInvoke.java:35-41`) | self-correct the named field |
| **Malformed JSON body** | `INVALID_ARGUMENT` | **no** field — `<traceId>,malformed JSON request body` (`UnaryMethod.java:243-249`) | fix the request JSON |
| **Unauth / forbidden** | `UNAUTHENTICATED` / `PERMISSION_DENIED` | terse auth reason, no field hint: `"requireCredential but empty token"` (`JwsVerify.java:401`), `"Token expired at: …"` (`:485`), `"kid not found or expired"` (`:448`), `"invalid signature !"` (`:454`) | fix the token — can't infer a valid call (gap **RPCURL-001**) |
| **Unimplemented / not found** | `UNIMPLEMENTED` (rpcurl) / `code:5` (agent HTTP: `{"code":5,"message":"Service/method not found"}`, `WebMethodRegistry.java:36-40`) | bare "method not found" | wrong/hidden method/service (hidden ≡ missing, by design) |

`INVALID_ARGUMENT` spans two shapes (validation = field-level; malformed JSON = bare). On the
HTTP face the rejected *value* is stripped even for validation errors (field path + constraint
only, PII in the log — §12.4).

### 15.5 Soft errors are a consumer UX obligation

> Consumer field case (2026-07-19); external doctrine, not a krpc-code fact.

A service returning a "processing / will converge later" soft error (UNAVAILABLE-class)
imposes an obligation on the **consumer**: translate it into a user-visible **intermediate
state** + **idempotent re-entry** — never toast the raw error while leaving the UI state
unchanged. A user who sees a bare error retries; if the request already took effect that is
a double-submit hazard. Reference shape: the action moves to an intermediate status (e.g. an
order → `refunding`), the soft error is mapped to **success semantics** for the caller,
retries are idempotent, and a **reconcile worker** converges the final state.

### 15.6 Consuming releases & smoke example → reference

`-rc` consumption routes (mavenLocal / vendored file-repo / Nexus — never shadows a Central
GA) and the LH end-to-end smoke script → `skills/krpc/references/operations.md`.

---

## 16. Operations facts

Ops/runtime facts that bite in production. Quick-ref below; observability contract,
zero-trace fault tree, no-span inference, and release/rollback runbook →
`skills/krpc/references/operations.md`.

### 16.1 Config: build-time vs runtime boundary

A native image bakes **build-time-consumed** config into the binary; **runtime-consumed**
config (`@ConfigProperty`/env, read at bean init) flips on the *same* binary — no rebuild.

- **General rule:** a value consumed in a *build* step (native static init / an Arc build
  item) is frozen into the image; a runtime env override does nothing.
- **Client routing URLs (`rpc.client.*.url`) — NOT verifiable here.** The build-bake concern
  was recorded as **EXTRPC-URL-001** (`benchmark/RESULTS.md:24-29` is that historical bake,
  **not** a fix); the runtime-URL fix lives in the `ext-rpc` repo (out of tree). No production
  `rpc.client.*.url`, Quarkus RUN_TIME root, or native override test exists here, so "routing
  keys are runtime-overridable" is **unverified here**.
- **Verify (proven for `rpc.server.defaultExecutor` only):** `RPC_SERVER_DEFAULTEXECUTOR`
  flips the VT-vs-pool branch on one native binary, seen in the startup log
  (`benchmark/RESULTS.md:93-104`). Apply the same env-flip to any runtime key.

### 16.2 Port authority

Three server faces, three ports — do not conflate.

| face | config key | default | speaks |
| --- | --- | --- | --- |
| gRPC gateway | `rpc.server.port` | **50051** (`RpcConstants.java:33` `DEFAULT_PORT`) | HTTP/2 gRPC (use `rpcurl`) |
| krpc HTTP (agent/MCP) | `http.port` | **8080** (`HttpHandlerExpose.java:36`, `HttpServer.java:32`) | plain HTTP/1.1 JSON (`/agent/*`, `/mcp`) |
| Quarkus REST | `quarkus.http.port` | **8080** (Quarkus default) | consumer's own Vert.x/REST |

All services keep the **same** default gRPC port (**50051**) — distinguish instances by K8s
**service name**, not by hand-assigning an incrementing port per app; the `50051`–`50058`
per-app style is single-machine legacy that new projects must not copy.

The krpc HTTP face is **its own netty server** (`new HttpServer(this, port)`,
`HttpHandlerExpose.java:86`), separate from Quarkus's Vert.x HTTP — both default to **8080**,
so a Quarkus consumer serving REST **collides**. A probe/`curl /agent/discover` at `:8080`
hitting Quarkus REST (404 / wrong body / bind conflict) looks like a broken endpoint but is a
port-identity mixup — reassign one face (e.g. `http.port` → `8088`). gRPC (50051) never collides.

---

## 17. Quick checklist for a new service

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
