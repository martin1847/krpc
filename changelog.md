# Unreleased

* **BREAKING — JSON scalar decoding is strict by default (#56).** A JSON number or boolean sent
  into a `String` target is now a decode failure instead of being silently stringified (`12345` →
  `"12345"`, `true` → `"true"`). Through 1.1.1 such a request passed field validation and reached
  the method body carrying a coerced value; only `[]` / `{}` failed. **A caller that sends a bare
  number where the DTO declares a `String` starts being rejected the moment you deploy, with no
  change on its side.** Scope is every typed decode through `JsonUtils.parse`: the gRPC request
  path, both `/agent/invoke` decodes, MCP tool arguments, and **client-side response decoding** —
  so a caller on 1.2.0 also reads its callee's replies strictly. Untyped `Map`/`Object` targets are
  unaffected (no textual slot, nothing to coerce). **Rollback needs no code change:
  `KRPC_JSON_STRICT=false`** — environment variable only, no system-property equivalent, resolved
  lazily on the first decode so it flips a deployed native binary without a rebuild. Unset or blank
  keeps strict; any unrecognised value (`fasle`, `yes`) falls to lenient, deliberately, because an
  escape hatch that only opens when spelled perfectly fails exactly when it is needed. Before
  upgrading, audit the `String` fields on your request DTOs and what actually reaches them — one
  downstream audit of ~70 such fields found zero exposure, which is a data point, not a promise.
  SPEC §4.
* **BREAKING — `/agent/invoke` and MCP report real gRPC error codes (#56).** Both HTTP agent faces
  flattened every dispatch failure: `/agent/invoke` answered a hardcoded `13` for everything, and
  MCP fell through to `Status.fromThrowable`'s `UNKNOWN` = `2`. A malformed body, a failed field
  validation and a genuine crash were indistinguishable, and all three claimed the server was at
  fault. `UnaryMethod.invokeWeb` now applies the same exception→`Status` table the gRPC path always
  used, and both handlers derive the code from it.

  | face | input | 1.1.1 | 1.2.0 |
  | --- | --- | --- | --- |
  | `/agent/invoke` | missing / null / empty / blank required field | `13` | **`3`** |
  | `/agent/invoke` | malformed JSON, or a scalar strict decoding rejects | `13` | **`3`** |
  | `/agent/invoke` | auth failure | `13` | **`16`** / **`7`** |
  | `/agent/invoke` | unexpected server exception | `13` | **`2`** |
  | MCP `tools/call` | malformed JSON, or a scalar strict decoding rejects | `2` | **`3`** |

  A *dispatched* `/agent/invoke` request never answers `13` again — a failure outside dispatch
  (building the request context, serializing the response) still does, and now means what it says.
  MCP validation and auth codes were already correct and do not move. **Branch on `code`, never on
  `message`:** the message text is graded per face (below) and is not a contract. SPEC §4.
* **Security — agent-face error messages are default-deny (#56).** Reporting the real status also
  meant echoing its description, which leaked two ways: an `INTERNAL`/`UNKNOWN` description carries
  the thrown class and its raw message (SQL fragments, connection strings, hostnames, file paths,
  and any input an exception interpolated), and auth descriptions distinguish "JWKS not ready" from
  "empty token" from "unknown kid" from "bad signature" from "expired", several quoting the `kid`,
  `exp` or client id back — a credential-state oracle for an unauthenticated caller. On
  `/agent/invoke` and `/mcp` only request-refusal codes (`INVALID_ARGUMENT`, `NOT_FOUND`,
  `ALREADY_EXISTS`, `FAILED_PRECONDITION`, `OUT_OF_RANGE`) now pass their description through, so
  parameter-validation still returns `field(constraint)` detail and business errors still reach the
  caller verbatim; everything else — including any code added later — collapses to a fixed string
  per code. gRPC is unchanged (service-to-service). Server-side logging is graded to match: a
  refused request is one bounded WARN with the description sanitized of control characters and
  capped, while a genuine fault keeps the full cause chain. If your service needs the caller to see
  detail on a withheld code, return it as a soft `RpcResult.error(code, msg)` instead. SPEC §4.
* **`Jwks.keys` is `List<Map<String,Object>>` (#56).** RFC 7517 §4 places no type constraint on JWK
  members, so a vendor extension carrying a number, boolean or array is a legal keyset — under
  strict decoding the old `Map<String,String>` would have failed the *entire* document over one
  such member, stranding the last-known-good keys at refresh or blocking bootstrap. `JwsVerify` now
  reads only the members it consumes and skips an individual unusable JWK (missing, non-string, or
  unbuildable key material) instead of rejecting the document. **`Jwks.getKeys()`'s signature change
  is source-incompatible and binary-compatible** (both erase to `List`): already-compiled consumers
  keep running, but code assigning to `List<Map<String,String>>` fails to compile until updated.
  `rpc-server` is outside the japicmp-covered set, so no gate catches this for you.
* **Business error codes: start at 1000 (suggestion, #56).** gRPC status occupies `0`–`16` and
  `17`–`999` is reserved for system codes krpc may add later, so a business code at `1000`+ cannot
  collide with either and is recognisable on sight as business semantics. Existing band widths are
  unchanged (hundreds per area, thousands for a large one). **The framework does not act on this** —
  nothing validates, reserves or routes on the range, and a code below `1000` works exactly as
  before. Do not write code that depends on it. `RpcResult` javadoc + SPEC §3.
* **Flag governance — kill switches are lazily resolved at a single point and log one line
  (#54, #55).** Enforced by an ArchUnit gate so the pattern cannot drift; a lazily-resolved flag is
  also what lets `KRPC_JSON_STRICT` flip on a native binary, where class initializers run at image
  build time and a static-block read would be baked in.
* **GraalVM/Mandrel 25 native metadata — migrated to `reachability-metadata.json`, deprecated
  `-H:` options removed (NATIVE-META-001).** Every published krpc jar now ships its native-image
  metadata at the standard auto-detected location `META-INF/native-image/tech.krpc/<artifactId>/`
  instead of passing `-H:ReflectionConfigurationResources` /
  `-H:DynamicProxyConfigurationResources` from `native-image.properties`. Those options are
  deprecated **and** experimental on Mandrel/GraalVM 25, so a consumer building 10 services saw the
  warnings once per krpc jar per module; they drop from 6 lines to 1 residual proxy deprecation (see below), and the
  stack survives the eventual removal of the legacy options — agent-era native readiness, since an
  agent reading a build log cannot tell a deprecation warning from a real defect. Each directory
  carries the modern combined `reachability-metadata.json` (GraalVM/Mandrel 24+; dynamic proxies now
  live in its `reflection` array as `{"type": {"proxy": [...]}}`) **and** the legacy
  `reflect-config.json` / `proxy-config.json`, because **GraalVM/Mandrel 21–23 ignore the combined
  file silently** — dropping the legacy pair while krpc's Java baseline is JDK 21 would un-register
  every framework type with no build-time signal. `native-image.properties` survives only where it
  carried non-metadata `Args` (`rpc-common`'s `--initialize-at-run-time`, the `rpc-server-*` builder
  `-J--add-exports`). No metadata entry changed meaning: 35 reflection types + 1 proxy migrated,
  set-equality asserted old-vs-new. Metadata for `rpc-server-quarkus` and `rpc-server-spring` also
  stops colliding — both previously shipped `META-INF/native-image/rpc-server/`, the same path in
  two jars. Residual: an auto-detected `proxy-config.json` still trips one
  `DynamicProxyConfigurationResources` deprecation line per build until the 21–23 floor is retired.
  Docs: SPEC §13 + `skills/krpc/references/native-image.md` §13.6 (with the verified
  toolchain-support matrix).
* **MCP 2026-07-28 (stateless) alignment for the `POST /mcp` agent-tool bridge.** Additive and
  backwards compatible — the bridge was already stateless (no session id, one self-contained
  JSON object per POST, no SSE), so 07-28 ratifies its shape rather than forcing a rewrite.
  Dual version track: `2026-07-28` / `2025-11-25` join `SUPPORTED_VERSIONS` and a 07-28 client
  sends **no `initialize`** — it states its version per request in
  `params._meta["io.modelcontextprotocol/protocolVersion"]` (the canonical, only accepted
  position). A *stated* version is always enforced, for every method: malformed shape or
  unsupported value → `400` + `-32600`; a request stating **nothing** stays on the legacy path,
  a deliberate dual-stack deviation from the 07-28 REQUIRED wording so pre-07-28 clients keep
  working. `initialize` + `ping` remain for the older line through the 12-month deprecation
  window, and `initialize` never negotiates `2026-07-28` (the revision that removed it) —
  ceiling and fallback are `2025-11-25`. New `server/discover` (MUST in 07-28) returns
  `supportedVersions` / `capabilities` / `serverInfo` / `instructions` / `ttlMs` /
  `cacheScope`, and being 07-28-only it *requires* a declared `2026-07-28` — a declared version
  must be compatible with the method called, so `initialize` / `ping` symmetrically reject a
  declared `2026-07-28` (that revision removed them). L7 header consistency:
  `Mcp-Method` / `Mcp-Name` disagreeing with the JSON-RPC `method` / `params.name` — including
  a header sent twice with distinct values, or a name header over a body with no usable
  `params.name` — → `400` + `-32020 HeaderMismatch` (a proxy routing on the header while the
  server executes the body is a split-brain surface). `tools/list` gains `ttlMs`/`cacheScope`
  and a deterministic name sort. An explicit `"id": null` is now `-32600` (invalid RequestId),
  not a notification. SSE resumability, sessions, MRTR / `input_required` and
  `subscriptions`/`listen` stay **design-exempt** (SPEC §12.2).
* **ext-rpc-gen 1.0.2 — agent-native client generator dependency fix (2026-07-19, GEN-NETTY-102).**
  1.0.1 regression on clean consumer classpaths: `Gen.scan` loads `RpcServerBuilder` →
  `NoClassDefFoundError: NettyServerBuilder` (rpc-server keeps grpc-netty `compileOnly` by
  design; gen is a leaf and must carry it). POM now declares `io.grpc:grpc-netty` (runtime,
  1.79.0) + a clean-classpath scan smoke test. Field-reported by the LH consumer ecosystem.
* **ext-rpc-gen 1.0.1 shipped standalone (2026-07-19, GENDET-002 #37).** DTO emission is
  topological (referenced-before-referencing, Tarjan SCC condensation, alphabetical
  tie-break) — fixes consumer-side TDZ under `emitDecoratorMetadata` from single-file TS
  output; cycles emit deterministically with a per-SCC WARN. The `tech.krpc.ext:ext-rpc-gen`
  coordinate versions independently of the krpc train.

# 1.1.1, 2026-07-18

OTel semantic layer + architecture gates + the full agent surface (AI-native code face complete). Merged to `dev` via PRs #22-#29 under heterogeneous adversarial review.

* **OpenTelemetry spans on both faces (ADR-0006, #23).** gRPC server/client interceptors + HTTP-face SERVER spans; W3C-only propagation; core stays API-only (`opentelemetry-api`/`-context`, NS-3) — `KrpcOtel.install()` explicit wiring (Quarkus consumers get it via CDI); no SDK on classpath = exact pre-existing behaviour (`noSdkTest` source set proves it).
* **OTEL-002 field fixes (#25).** HTTP face binds inbound W3C context into MDC around the handler body (root cause of the reported webhook chain break — the gRPC face already did this); `LogRedact` mask-by-default allow-list redaction for headers/cookies at DEBUG (fixes JWT-in-log), URIs logged path-only; `TraceMeta.parse` strict for version 00 AND W3C forward-compatible (higher non-ff versions keep opaque trailing fields, forwarded verbatim); gRPC face validates before any trace MDC write. Ghost CLIENT spans did not reproduce in-process; export-boundary cause tracked with consumers.
* **AGENT-002 — MCP error envelope + DX (#27).** tools/call errors return `{code,message,violations?:[{field,constraint}]}` — never rejected values (validation carries a typed `ValidationException`; the classic gRPC description keeps field-level self-correction as `Dto : field(message)`, value-free). Unknown tool → did-you-mean; empty tool face → namespaced `_meta` hint; no method-name echo as description when `@Doc` absent; `serverInfo` = app name + real build version (generated `BuildVersion`); **method-level `@UnsafeWeb.AgentTool`** exposes a per-method subset (interface-level `agentTool=true` unchanged; defaults all-OFF, NS-6).
* **`KRPC_BIND_HOST` / `rpc.server.bindHost` (#28).** Optional listen address for both the gRPC and krpc-HTTP faces (e.g. `127.0.0.1` behind a reverse proxy). Unset = wildcard, exactly as before.
* **Architecture gates (#22, #26, test-only).** ArchUnit freeze-ratchet baseline (ADR-0005) + scan-face completeness guard on Gradle's evaluated project model (new core module not covered by arch-test = red, stale-inventory-proof) + NS-1 no-proto gate + NS-4/NS-6 default assertions.
* **SPEC (#24).** New §14 contract evolution (japicmp discipline; major stays 1, minor = breaking, patch = compatible), §15 consumer guide, §16 operations facts (port authority table, zero-trace fault tree); condensed to 749 lines with reference extractions.
* **Try it live (#29).** Public sandbox `https://demo.krpc.tech` (MCP + `/agent/*` + gRPC over TLS; fake data).

* **⚠️ `AppEnv.PRE` removed — renamed to `AppEnv.STAGING`. Source-compat note.** The `APP_ENV` deployment-environment enum is now `DEV/TEST/STAGING/PROD` (was `DEV/TEST/PRE/PROD`). `PRE` had zero references across the krpc ecosystem (grepped krpc + ext-* + downstream consumers), so it was deleted outright rather than kept as a `@Deprecated` alias; any caller on `AppEnv.PRE` must switch to `AppEnv.STAGING`. (`rpc-common/.../util/EnvUtils.java`.)
* **⚠️ `APP_ENV` parsing hardened — an unknown value no longer crashes startup.** `EnvUtils.current()` previously did `AppEnv.valueOf(env.toUpperCase())`, so any value outside the enum (e.g. IAC's `stage`/`pre`) threw `IllegalArgumentException` on the startup-banner path and **aborted boot**. Parsing is now case-insensitive with alias normalisation (`pre`/`stage`→STAGING, `production`→PROD, `develop`/`development`→DEV) and **never throws**: an unknown value warns once and falls back to `PROD` (safe side, same fail-closed philosophy as auth). Unset `APP_ENV` still defaults to `DEV`. SPEC §12.5. Test: alias matrix + unknown-value no-throw.
* **Executor init log now tells the truth.** `RpcServiceExpose` / `RpcServiceExposer` dropped the dead `cpus<6→6` computation and the misleading `" cpus is too small … change to default 6."` + `"Init Executor …({} cpus)…"` logs — since ADR-0002 the server executor is virtual-thread-per-task (no bounded pool; `ThreadPool.newExecutor`'s `base` arg is unused), so the CPU math never took effect and only alarmed operators reading it in a container. Now logs `Init virtual-thread per-task executor {}, replacing grpc ServerImplBuilder.DEFAULT_EXECUTOR_POOL`. `ThreadPool.newExecutor(String)` added; the `(String,int)` overload is now `@Deprecated` and forwards to it.

# 1.1.0, 2026-07-04

* **Dependency: `rpc-server-quarkus` now pulls `tech.krpc.ext:ext-rpc` `1.0.1` → `1.0.3`** (native-image DTO super-class reflection fix — the Quarkus augmentation `Class.forName` CNFE, now a Jandex super-walk that also registers inherited/generic-base DTO fields). The `extRpcVersion` / `extMybatisVersion` gradle properties were split so the two extensions version independently (`ext-mybatis` stays `1.0.1` until its own bump).

P0 fix package (AGENT-001, ADR-0004) — makes the already-shipped agent surface actually work in a default consumer; these are bug fixes, not flag-gated behaviour changes:

* **Agent HTTP endpoints reachable in a default Quarkus consumer.** `AgentDiscoverHandler` / `AgentInvokeHandler` are discovered reflectively by `HttpHandlerExpose` (`getBeans(Object, @Any)`), so Arc's default `remove-unused-beans=all` stripped them and `/agent/discover` + `/agent/invoke` were absent (HTTP server logged `Skip HTTP Server , no Handlers found.`). Both handlers now carry `@io.quarkus.arc.Unremovable`; the endpoints work with zero consumer action. Container-level `@QuarkusTest` added (the prior unit tests instantiated handlers with `new`, bypassing the container, and missed this).
* **Native reflection metadata for the agent invoke path.** `AgentInvokeRequest` registered in `rpc-server-quarkus` reflection-config so `POST /agent/invoke` deserializes in native mode (the discover `ApiMeta` closure was already covered).
* **`AgentInvokeHandler` javadoc corrected**: not-found/forbidden surface as JSON `code:5` (gRPC `NOT_FOUND`), not HTTP `404` (the netty transport only emits 200/404/500 at the status line; errors ride the JSON `code`).
* **Server concurrent-call cap (CVE-2026-47244 app-layer defence-in-depth, D2).** The Netty gRPC server now sets `maxConcurrentCallsPerConnection`, new config `rpc.server.maxConcurrentCallsPerConnection` (**default 2000**, `0` = unlimited = pre-1.0.4 behaviour). Advertised as HTTP/2 `SETTINGS_MAX_CONCURRENT_STREAMS`, so a high-concurrency single-channel client is **back-pressure queued** (excess streams wait client-side), not failed. Complements the 1.0.3 Netty 4.1.135 bump (transport-layer fix) with an app-layer bound. SPEC §12.1. Integration test asserts the over-cap call queues (not rejected) and in-flight concurrency stays ≤ cap.

P1 MCP bridge (AGENT-001, ADR-0004 P1 re-scoped to a thin bridge) — flag-gated, default OFF:

* **MCP Streamable HTTP bridge (`POST /mcp`).** Hand-written Model Context Protocol endpoint (spec `2025-06-18`, JSON-RPC 2.0) on the existing `http-server` netty host, same process as `/agent/*` — no third-party MCP SDK, no new module or Central artifact. `tools/list` is generated from the live `ApiMeta` (`inputSchema`/`outputSchema` from the DTO type tree + jakarta constraints + `@Doc`, `RpcResult<T>` unwrapped); `tools/call` dispatches through the same `WebMethodRegistry.invokeWeb` path as `/agent/invoke` (credential **not** bypassed). Methods: `initialize`, `notifications/initialized` (202), `tools/list`, `tools/call`, `ping`; JSON-response mode (no SSE); `GET /mcp` → 405, unsupported `MCP-Protocol-Version` → 400. Gated by `rpc.server.mcp.enabled` (env `KRPC_MCP`), **default OFF = byte-level zero new surface**. Verified with real MCP clients on **JVM and GraalVM native** (Mandrel 25/JDK25): `initialize` captured via a `@modelcontextprotocol/sdk` client script, `tools/list` + `tools/call` via the official `@modelcontextprotocol/inspector` CLI; `initialize`+`tools/list` byte-identical across both modes, `tools/call` differs only in the runtime timestamp (verbatim transcripts: `docs/mcp-transcripts/jvm.txt` + `docs/mcp-transcripts/native.txt`). OFF-path regression (`/mcp` absent) covered separately by `McpDisabledQuarkusTest`. Contract in SPEC §12.2.
* **`@UnsafeWeb(agentTool=true)` opt-in** (default `false`): MCP tools are a strict subset of `@UnsafeWeb` — the `/agent/discover` web view is unchanged, the two surfaces are distinct. ON with no agentTool method = empty tools list.
* **ADR-0004 revised** (status stays accepted): P1 re-scoped from a standalone runtime MCP module to a thin bridge on P0; records the SDK evaluation (official `io.modelcontextprotocol.sdk:mcp` is Reactor + servlet/spring, not embeddable in krpc's native-zero-glue model → hand-written) and the JSON-only/no-SSE transport decision.

HARDEN Batch 1 (AUDIT-001: C4/C5/O1/O3/O4 + O-sec) — auth trust-root hardening. **⚠️ BEHAVIOUR CHANGE — READ BEFORE UPGRADING.** These fixes make the JWT/JWKS auth contract actually hold; several change observable default behaviour:

* **⚠️ FAIL-CLOSED on JWKS load failure (was fail-open). BREAKING default.** Previously, if the JWKS URL was unreachable/bad at startup and `exitOnJwksError=false` (the default), krpc logged a warning and **left authentication disabled** — every credential-required request passed with no check until restart. Now the verifier is **always registered** and **fail-closed**: while JWKS has never loaded, every credentialed request is rejected with gRPC `UNAVAILABLE` ("JWKS not ready"), and a background daemon retries the fetch with gentle backoff (5s→60s) until it succeeds (then auth goes live automatically, logged). `exitOnJwksError=true` still aborts startup loudly (unchanged). **There is no longer any config that yields silent fail-open.** If you relied on the old default to run with a broken JWKS, auth now blocks those requests. (`JwsVerify.bootstrap`/`bootstrapAndRegister`; O1.)
* **⚠️ `nbf` (not-before) is now enforced** when present in a token, with a 60s clock-skew allowance. A token whose `nbf` is more than 60s in the future is rejected `UNAUTHENTICATED`. Tokens without `nbf` are unaffected. (`JwsVerify.verify`; O-sec-17.)
* **Malformed tokens now map to `UNAUTHENTICATED`, not `UNKNOWN`/500.** Token parse / base64 / JSON / missing-`exp` failures return a clean `UNAUTHENTICATED` and no longer log a full stack trace per bad token (log-flood DoS closed; logged at DEBUG only). Status code for these cases changed from `UNKNOWN` to `UNAUTHENTICATED`. (`JwsCredential`/`JwsVerify`; C5.)
* **Signature malleability closed.** ES256 signatures must be exactly 64 raw bytes (R‖S); bare ASN.1/DER signatures (previously accepted) and empty/short arrays (previously an `ArrayIndexOutOfBounds`) are rejected `UNAUTHENTICATED`. Only the canonical concat form is verified. (`Es256Jwk.isValid`; O-sec-16.)
* **Key revocation/rotation now takes effect.** Each successful JWKS fetch **rebuilds** the key map (replace, not merge), so a key removed from the published JWKS stops verifying. A cache hit older than 5min triggers a background refresh; an unknown `kid` refetches on a shorter 30s backoff, and a **failed** fetch no longer burns the 5min freshness window (keeps last-known-good keys serving). (`JwsVerify`; O3/O4.)
* **JWKS fetch is now bounded (C4).** Replaced `URL.openStream()` with `java.net.http.HttpClient` (5s connect / 10s request timeout) and a 1 MiB response-body cap (anti-OOM against a hostile/oversized JWKS). Uses a `ReentrantLock` (not `synchronized`) so the fetch never pins a virtual-thread carrier.
* **Empty/null JWKS keys fail-closed (O-sec-47).** A `{"keys":[]}` / null-keys document never NPEs. Before the first successful load a fresh verifier stays not-ready → `UNAVAILABLE`; once ready, an empty fetch is treated as **full revocation** (see fix-round-1 below).
* **Private-key material never logged (O-sec-48).** `Es256Signature` key-load failures no longer include the base64 private key in the error log.
* **New (opt-in, default OFF): `rpc.server.jwsAudiences`** — comma-separated `aud` allow-list. Empty (default) = no `aud` check (behaviour unchanged); when set, a token whose `aud` does not intersect the list is rejected `UNAUTHENTICATED`. Hook + docs only this batch; single-`aud` deployments are unaffected. (O-sec-17 `aud` sub-item.)
* **De-dup:** the spring + quarkus `InitJwsVerify` load/register logic is unified in `JwsVerify.bootstrapAndRegister` (framework modules only bind config), so the anti-fail-open invariant lives in one place. Regression tests: fail-open guard (spring + quarkus), malformed-token matrix, signature malleability, kid rotation/revocation, throttle-window survival, empty-keys fail-closed. SPEC §8 updated.
* **HARDEN Batch 1 fix-round-1 (codex review r1 follow-up)** — three "malformed input escapes as `UNKNOWN`" leaks + one revocation regression, each with a regression test:
  * **Degenerate 64-byte signatures no longer escape as `UNKNOWN`/`PERMISSION_DENIED`.** An exactly-64-byte ES256 signature with an all-zero S (indexed one past the array end → `ArrayIndexOutOfBounds` → `UNKNOWN`) or all-zero R (zero-length DER integer → `PERMISSION_DENIED`) now maps to `UNAUTHENTICATED`: `Es256Jwk.jws2der` rejects degenerate R/S as `IllegalArgumentException`. (O-sec-16.)
  * **Missing/blank `kid` → `UNAUTHENTICATED`, not `UNKNOWN`.** A well-formed header with no `kid` previously hit `jwksCache.get(null)` → `NullPointerException` → `UNKNOWN`; `JwsVerify.verify` now rejects a null/blank `kid` before the map lookup.
  * **Revocation regression: a ready verifier now stops serving old tokens after an empty/null-keys refresh.** Previously a post-ready fetch that returned `{"keys":[]}`/`{}` **threw**, and the throw was swallowed by the best-effort refetch path, leaving the **stale keyset live** — a fully-revoked JWKS kept verifying. A successful fetch yielding zero usable keys now **replaces the live map with an empty one** (fail-closed, every `kid` → `PERMISSION_DENIED`) instead of throwing; genuine fetch failures still retain last-known-good. (O3.)
  * **advisory:** `aud` accepted as a single string or an array (RFC 7519; single-string `aud` previously `ClassCastException` → `UNKNOWN` when `requiredAudiences` enabled); JWKS **body** consumption now runs under a wall-clock deadline (`bodyReadTimeoutMillis`, default = request timeout) so a slow-drip body can't pin the fetch thread past the 1 MiB size cap.

HARDEN Batch 2 (AUDIT-001: C1/C2/C8/O2 + O10) — client correctness. **⚠️ ONE BEHAVIOUR CHANGE — the default client deadline (O2). The other four are pure bug fixes.**

* **⚠️ Default client call deadline is now 30s (was: none / infinite). BEHAVIOUR CHANGE.** Before, an outbound call with no explicit deadline used `CallOptions.DEFAULT` — no deadline at all — so a hung / half-open upstream blocked the calling virtual thread **forever**. Now every deadline-less outbound call gets a configurable default (`rpc.client.defaultDeadlineMillis`, **default 30000**). **A legitimate call slower than 30s will now be cut with gRPC `DEADLINE_EXCEEDED` — if you have long-running calls, set a larger value or an explicit per-call deadline.** `0` or negative = unlimited = pre-1.0.4 behaviour. An explicit deadline (`ClientContext.withCallOptions` / a filter-set `CallOptions.getDeadline()`) always wins. The apply logic is one authority (`ClientDeadline.apply`, `rpc-client`), shared by the proxy path (sync + async), `GeneralizeClient`, and rpcurl; Spring binds the config in `RpcClientAutoConfigure` (single point, no Spring/Quarkus drift — mirrors the Batch-1 `JwsVerify.bootstrapAndRegister` dedup). SPEC §12.3. Test: default applied when unset; explicit deadline preserved; `0` = unlimited. (O2, `ClientContext.java`.)
* **Default client cache is now thread-safe (data race fixed).** `SimpleLRUCache` is a bare `accessOrder=true` `LinkedHashMap`, so even `get()` structurally re-links (moves the entry to the tail). Under virtual-thread concurrency an unsynchronized `get()` corrupted the linked list — dirty reads, and once a 100% CPU self-spin on a broken next-pointer. `get()`/`set()` are now `synchronized` on one monitor (the check-timestamp-then-remove compound is one critical section). The cache stays opt-in (`@Cached`); this is a correctness fix, not a redesign. Test: N-thread × M-round concurrent get/set over a key space wider than capacity (eviction + reorder under fire), asserting no corruption / no hang. (C1, `SimpleLRUCache.java`.)
* **Cache key collision on `byte[]` params fixed (`@Cached` returned wrong data).** `byte[]` params travel in the proto `bs` field with `utf8` **empty**, and `cacheKey()` keyed only on `getUtf8()` — so **every** `byte[]`-arg call on a method collapsed onto one key and `@Cached` served another argument's response. The key now tags the `dataCase` (`u:` utf8 / `b:` md5 of the bytes / `n:` empty) so the three input shapes never collide. Test: two distinct `byte[]` inputs → distinct keys, no cross-serve. (C2, `CacheManager.cacheKey`.)
* **Cached `byte[]` values are now defensively cloned (no shared-reference poisoning).** The `byte[]` cache path stored `OutputProto.getBs()` and later returned it via `setBs()` **by reference** — a caller mutating a returned array corrupted the shared cache entry for every other thread/call. `set` and `get` now `clone()` the array on both edges. Test: mutate a returned `byte[]`, re-get, assert the cached value is intact. (O10, `CacheManager` set/get.)
* **Spring client channel leak + missing-port fixed.** In `RpcClientScannerConfigurer`, the `RpcClientFactory` was a local var captured only by the client bean-supplier lambda and **never registered as a bean**, so its `close()` was never called on context shutdown — every refresh leaked the `ManagedChannel` + its gRPC executor threads. The factory is now registered as a `destroyMethod="close"` bean. Also, a config URL without a port gave `url.getPort() == -1` → `forAddress(host, -1)`; it now falls back to the protocol default (`http`→80, `https`→443), matching rpcurl. **Plus C6-client:** `RpcClientFactory.close()` now does a graceful `shutdown()` + bounded `awaitTermination(5s)` before falling back to `shutdownNow()`, instead of an immediate `shutdownNow()` that cancelled in-flight RPCs. Test: context close triggers `factory.close()` (channel shut down); portless URL → default port. (C8 + C6-client.)

HARDEN Batch 3 (AUDIT-001: C3/C6/C9/C10/O5 + AUD-omp-08/09/11/12/13/28/30/31) — server-core robustness. **⚠️ Some fixes change observable default behaviour — READ BEFORE UPGRADING.** Same fail-closed defining as Batch 1: these make an existing contract actually hold, they are not new features. HTTP-face items (O6/C7) are deferred to Batch 4; this batch does not touch `http-server`.

* **⚠️ `RpcResult` envelope invariants are now enforced at runtime (were `assert`-only → no-ops in production `-da`).** `RpcResult.ok(null)` now throws `NullPointerException`; `RpcResult.error(code,msg)` throws `IllegalArgumentException` when `code<=0` and `NullPointerException` when `msg==null`. Pre-fix these were `assert` statements, silently skipped under the default `-da` JVM, so a malformed envelope sailed through. Authoring code that already followed SPEC §2 is unaffected. (C3 + AUD-omp-28; `RpcResult.java`.)
* **`RpcResult.ifOk` no longer mutates its receiver (aliasing fix).** The OK branch built its result by re-typing `this` and overwriting `this.data` — any caller still holding the original reference silently saw its DTO swapped. `ifOk` now returns a **new** `RpcResult` and leaves the receiver untouched. `error()` (the no-arg reinterpret-as-failure) now throws `IllegalStateException` if called on an OK result (code==0) — there is no error to forward. (C3 + AUD-omp-28.)
* **StreamObserver terminal calls are now idempotent (double-close closed).** `UnaryCallObserver.onError`/`onCompleted` set their terminal flag **before** `call.close()` and no-op on any subsequent terminal call, so a second `onError`/`onCompleted` (e.g. the unary catch firing after `onCompleted`'s `close()` threw) can no longer trigger `IllegalStateException("call already closed")`. **The first real terminal signal wins and is never masked** by a spurious second one. (O5 + AUD-omp-08; `UnaryCallObserver.java`, `UnaryMethod.java`.)
* **Client cancellation now short-circuits the server's terminal sends.** `UnaryCallHandler.onCancel` sets the `cancelled` flag unconditionally (was write-only, set only in the no-callback branch), and `onNext`/`onError`/`onCompleted` short-circuit on `call.isCancelled()` — a cancelled (already-closed) call is no longer raced by a terminal `close()`/`sendMessage`. (Server does not yet interrupt a running handler body — synchronous invoke; that is intentionally out of scope.) (AUD-omp-12; `UnaryCallObserver.java`, `UnaryCallHandler.java`.)
* **⚠️ Graceful shutdown (was a hard `shutdownNow()` that cut in-flight RPCs).** New `RpcServerBuilder.shutdown(Server, Duration)` — the single shutdown authority — does `server.shutdown().awaitTermination(grace)` then `shutdownNow()` as a backstop; default grace `RpcServerBuilder.DEFAULT_SHUTDOWN_GRACE` = 30s. Both the spring and quarkus exposers route their `@PreDestroy` through it (de-dup: neither hand-rolls a `shutdownNow()` anymore — same discipline as Batch 1 `JwsVerify.bootstrapAndRegister`). On shutdown, in-flight RPCs now DRAIN instead of being aborted. (C6 + AUD-omp-11; `RpcServerBuilder.java`, `RpcServiceExposer.java`, `RpcServiceExpose.java`.)
* **⚠️ A mis-declared `@RpcService` method now FAILS FAST at discovery (was silently dropped).** Pre-fix, an interface method whose return isn't `RpcResult<…>` or which takes >1 parameter was silently filtered out — the service started clean and the method just failed to resolve at runtime (SPEC §1 landmine). Now an **abstract** public interface method with an illegal signature throws `IllegalStateException` at `RefUtils.toRpcMethods` (server startup / client stub init / codegen). `default`/`static` methods (and Object redeclarations) are the sanctioned escape hatch for helpers: they are exempt from the signature check and — as of fix-round-1 below — are **excluded from registration entirely** (a `default`/`static` method carries a body ⇒ it is a helper, never an endpoint; e.g. `DemoService.saveImg` is not a registered endpoint). If you had a mistakenly-illegal method that was silently dropped, it now surfaces loudly — make it a `default`/`static` helper or fix the signature. (C9 + AUD-omp-09; `RefUtils.java`. SPEC §1/§5 updated.)
* **`InputProto` wire parsing no longer mis-reads EOF or silently truncates.** (1) The constructor no longer treats `InputStream.available()==0` as end-of-message — `available()` is a best-effort hint that can legitimately return 0 for a stream that still has data; EOF is now decided by the decoder (`readTag()==0`). (2) An unknown field tag is now **skipped by its wire type** (forward-compat) instead of the pre-fix `default: done=true` that silently truncated every remaining field; a genuinely invalid wire type throws `InvalidWireTypeException` (surfaced as an error, never a quiet truncation). (C10 + AUD-omp-30; `InputProto.java`, `StreamDecoder.java`.)
* **`ServerContext` credential state no longer corrupts on a swallowed soft-auth failure.** `verifyed` is now set true only **after** `verify()` returns cleanly (was set before the call); a thrown `verify()` leaves it false so a retry re-verifies. `uid()` now throws a clear `IllegalStateException` instead of a `NullPointerException` when no credential resolved. Pre-fix, `softUid()` swallowing a verify failure left `verifyed=true`+`credential=null`, so a later `uid()` 500'd on the NPE. (The audit confirmed the "auth gate bypass" reading was unreachable; the real defect was the misuse-500.) (AUD-omp-13; `ServerContext.java`.)
* **⚠️ Malformed JSON request bodies now map to `INVALID_ARGUMENT`, not `UNKNOWN`, and no longer leak Jackson internals.** `JsonUtils.parse` throws a typed, message-sanitized `JsonDecodeException` (was a bare `RuntimeException` carrying Jackson field/class names and offsets). On the server request path `UnaryMethod` maps it to gRPC `INVALID_ARGUMENT` with a neutral description (the raw cause stays in `log.error` only). The JWT verify path still maps it to `UNAUTHENTICATED` (unchanged — it already catches `RuntimeException`). The `JsonUtils` static-init `System.out.println` is now a logger call. (AUD-omp-31; `JsonUtils.java`, `JsonDecodeException.java`, `UnaryMethod.java`.)
* **De-dup:** graceful-shutdown logic lives once in `RpcServerBuilder.shutdown`; the spring + quarkus exposers only bind their lifecycle to it. Regression tests: RpcResult contract + ifOk-aliasing, observer idempotency (incl. first-error-not-masked + close-throws), cancellation short-circuit, RefUtils fail-fast matrix (incl. default/static exemption), InputProto boundary (available()==0, unknown-tag-no-truncation, invalid-wire-type-throws), ServerContext soft-then-uid, malformed-JSON→INVALID_ARGUMENT/sanitized, and an in-process graceful-drain IT. SPEC §1/§2/§5 updated.
* **HARDEN Batch 3 fix-round-1 (codex review r1 follow-up)** — three blocking "the guard was drawn too small, one entry path leaked", each with a regression test:
  * **A legal-signature `static` helper is no longer registered as a phantom endpoint.** The fail-fast *illegal*-signature check already exempted `static`/`default`, but the final *registration* filter still kept any `RpcResult<…>`/≤1-param method — so a legal `static RpcResult<T> helper()` (which `Class.getMethods()` returns for an interface) slipped through as a callable endpoint. Registration now filters on `RefUtils.isDeclaredRpcEndpoint` (the single "is an endpoint" definition — excludes `static`/`default`/Object), the same predicate the fail-fast loop uses. **This also excludes legal `default` endpoints** (e.g. `DemoService.saveImg`): a method with a body is a helper, never an RPC. (C9; `RefUtils.java`.)
  * **The `code>0` invariant now holds on EVERY `error(...)` factory, not just `error(int,msg)`.** `RpcResult.error(CommonCode.OK)` (and any zero/negative `CommonCode`) built a `code==0` "error" — an envelope that `isOk()==true` yet was authored as a failure; `error(RpcResult)` likewise forwarded an OK result as an error. Both single-arg overloads now throw `IllegalArgumentException` on `code<=0`, closing the last paths around the `error(int,msg)` guard. (C3; `RpcResult.java`.)
  * **`onNext`'s throwing send path can no longer trigger a second `close()`.** The r1 top-of-method `onError`/`onCompleted` guards did **not** cover this: when `onNext`'s `sendHeaders`/`sendMessage` threw (call already closed transport-side), neither `aborted` nor `completed` was set, so `UnaryMethod`'s catch called `onError` → a second `call.close()` → `IllegalStateException("call already closed")` that **masked the real send failure**. Structural fix (not another per-method guard): a **single `terminal` gate**, checked-and-set immediately before every `call.close()` — shared by `onNext`'s send path (latches it on failure), `onError`, and `onCompleted`. `close()` is idempotent; the first terminal outcome wins and the true first error is never masked. The split `aborted`/`completed` flags are gone. (O5 + AUD-omp-08; `UnaryCallObserver.java`.)
  * **advisory (accepted, not changed):** client cancellation short-circuits the terminal *send* but does **not** interrupt a running handler body — this is intentional scope (synchronous invoke; `Thread.interrupt` semantics are high-risk), as the goal text and Batch 3 changelog already state. Full cancel propagation (interrupting the handler) is recorded as a follow-up. (AUD-omp-12.)

HARDEN Batch 4 (AUDIT-001: C7/O6 + AUD-omp-09/20/21/52 + omp-37 dead-code) — HTTP face + dead-code cleanup. **⚠️ HTTP error-code semantics change — READ BEFORE UPGRADING.** Two independent commit trails (HTTP hardening / dead-code removal); the dead-code removal changes **no** behaviour. First HTTP-face batch — Batch 3 deferred O6/C7 here.

Part A — HTTP face (`http-server`, `AbstractHttpHandler`/`HttpServer`):
* **⚠️ HTTP errors now differentiate `400`/`413`/`500` (was: nearly everything → `500` with the raw exception message on the wire).** Every error is now a uniform JSON envelope `{"code":<httpStatus>,"message":<text>}` with `content-type: application/json` (body/type always match — `404` previously declared PLAIN but wrote JSON). Malformed JSON body → `400` (neutral message; Jackson internals only in the log); empty body to a validating endpoint → `400` `"request body is required"` (was null → handler NPE → 500); bean-validation failure → `400` carrying field path + constraint but **never the rejected value** (PII stays in the log); body >1 MiB → `413`; handler internal error → `500` **status reason phrase only, never `ex.getMessage()`** (AUD-omp-21: no internal disclosure to agent/MCP clients). If you parsed the old text/500 error shape, switch to the JSON envelope + status line. (C7 + AUD-omp-21; `AbstractHttpHandler.java`. SPEC §12.4.)
* **Malformed `/agent/invoke` body no longer resets the connection (AUD-omp-20).** `parsePost`'s `JsonUtils.parse` ran outside the response try, so a bad body escaped to netty `exceptionCaught` → `ctx.close()` → a bare TCP reset with no HTTP response. It is now a `400` JSON and the connection stays open — the HTTP-face analogue of Batch 3's `JsonDecodeException`→`INVALID_ARGUMENT` (and symmetric with `McpHandler`'s internal `-32700`). (AUD-omp-20.)
* **HTTP entry no longer blocks the netty NIO eventLoop (O6).** `handler.handle()` (possibly blocking on DB/downstream) ran on the bounded netty worker eventLoop — a slow handler starved every other connection it served. `handle()` is now dispatched to a per-request **virtual thread**; the response write is scheduled back on the channel's eventLoop (netty model: writes belong to the eventLoop). Backpressure: `setAutoRead(false)` while a request is in flight bounds in-flight blocking work to ≤1 per connection, re-armed once the connection's queue drains. **Same-connection responses are returned in strict request order** via a per-connection FIFO — see fix-round-1 below (the initial O6 commit dispatched each request to its VT with no per-connection ordering, so requests pipelined in one TCP segment could reorder / cross data; that window is now closed). No `voidPromise`/AUD-omp-50 entanglement. (O6 + AUD-omp-09; `AbstractHttpHandler.java`. SPEC §12.4.)
* **`HttpServer` bind-failure group leak + slow-loris idle reaper (AUD-omp-52).** `start()` allocated both `NioEventLoopGroup`s then bound; a bind failure leaked their NIO threads and a caller retry loop stacked orphaned pools — now both groups are shut down before rethrowing. Pipeline gains an `IdleStateHandler` (reader-idle `READ_IDLE_SECONDS`=60s) so a connection that opens and sends nothing is reaped instead of pinning a worker forever. (AUD-omp-52; `HttpServer.java`, `AbstractHttpHandler.userEventTriggered`.)
* **Tests:** `HttpErrorMappingTest` (8 EmbeddedChannel cases — malformed→400 neutral, no-reset, empty-body→400, validation no-PII, 404 JSON, oversize→413), `HttpServerBindLeakTest` (bind-fail shuts down both groups), `HttpVirtualThreadDispatchTest` (handle() on a virtual thread, handler-error→500 neutral, slow request does not block another connection, autoRead re-arms on keep-alive, **pipelined requests return in request order** — the fix-round-1 guard) — all teeth-checked against pre-fix code. `gradle :http-server:test` green (14).

Part B — dead-code removal (AUD-omp-37; pure deletion, **no runtime/behaviour change**; see the compatibility note below): 14 all-commented / zero-reference files + 1 dead public API (`RpcClientFactory.setDefaultCacheManager`) removed, each grep-verified zero live reference before deletion; repo-wide `build -x test` compile-green = the zero-reference proof. Includes the **`io.netty.handler.ssl.ReferenceCountedOpenSslEngine` time-bomb** (183 lines, 100% commented, sitting in netty's own package — uncommenting would shadow the real netty TLS class), plus `HealthGrpc`, quarkus `GraalvmBuild`/`Target_io_netty_..._InternalLoggerFactory`/test `RpcConfig`, `plugin/{Plugin,AbstractPlugin}`, `context/{ContextUtil,DiContext,DiContextSimpleImpl}`, `exe/AbortPolicyWithReport`, `TsClientBulider`, `ext/{ClientConfiguration,Remote}`. Kept (live refs found, per verify-then-delete): `rpc-client/ext/GraalvmBuild` (called by `RpcClientFactory`), `AsyncClient`/`AsyncMethod` (used by test-api), `WireFormat`/`MD5`/`ParameterizedTypeImpl` (live refs).
  * **Compatibility note (source/binary — be honest to external consumers).** "No behaviour change" is true *inside this repo* (zero live references), but the removed items that were `public` and once compiled are **source- and binary-incompatible** for any out-of-tree consumer that referenced them: **`public class tech.krpc.client.ext.ClientConfiguration`**, **`public class tech.krpc.client.ext.Remote`** (+ its nested `Remotes`), and the **`public static RpcClientFactory setDefaultCacheManager(CacheManager)`** method. These were **never a promised/documented API** — undocumented, experimental/internal client-config surface with no callers — so they are removed as part of the 1.0.x pre-GA cleanup rather than deprecated-then-removed. If you depended on any of them, pin ≤1.0.3 or migrate to the supported client-config path. (No deletions are reinstated; this note only makes the wording honest to external consumers.)

HARDEN Batch 4 fix-round-1 (codex review r1 follow-up) — one blocking + two advisories:
* **blocking — same-connection pipelined responses can no longer reorder / cross data (O6).** The initial O6 commit's `setAutoRead(false)` only stops the *next* socket read; multiple `FullHttpRequest`s already decoded by the aggregator from a **single TCP segment** still fired `channelRead0` back-to-back and were each dispatched to a virtual thread concurrently — so a fast request could have its response written before an earlier slow one, i.e. a response framed against the **wrong** request on the same connection (HTTP/1.1 requires strict in-order responses). Fixed with a **per-connection FIFO** (`ConnState` in a netty channel attribute, touched only on the eventLoop): `channelRead0` enqueues each request (handler *and* error/404 responses alike, so a 400 can't overtake an in-flight slow response); exactly one request is served at a time; the next is dispatched only after the current response is written on the eventLoop. `autoRead` backpressure is retained (dropped while serving, re-armed when the queue drains). New teeth-checked guard `HttpVirtualThreadDispatchTest.pipelinedRequestsReturnInRequestOrder` writes SLOW+FAST in one socket write and asserts responses return in request order with each body against its own request — it reddens on the pre-fix handler (fast `{"ok":true}` arrives first, framed as SLOW's response). (O6; `AbstractHttpHandler.java`.)
* **advisory — starvation test renamed to what it proves.** `slowHandlerDoesNotStarveFastRequests` used two client connections, which may land on different eventLoops and so does **not** prove same-eventLoop non-starvation; renamed `slowRequestDoesNotBlockAnotherConnection` (per-connection independence). The genuine off-eventLoop evidence remains `dispatchRunsOnVirtualThread` (handler leaves the netty worker). No overclaiming test name.
* **advisory — dead-code removal compatibility wording made honest.** Part B's "no behaviour change" is repo-internal only; the removed `public` symbols break source/binary compat for out-of-tree consumers. The Part B compatibility note above now lists them explicitly and states they were unpromised internal/experimental API removed in 1.0.x cleanup (deletions unchanged, wording only).

# 1.0.3, 2026-07-02

* **grpc aligned to the Quarkus 3.33 LTS BOM: `io.grpc` 1.82.0 -> 1.79.0** (NATIVE-001 Option A). Kills the consumer-side `resolutionStrategy` force previously required for Quarkus native builds; wire behavior unchanged. SPEC §13.1 support matrix.
* **Netty security wave: 4.1.133 -> 4.1.135.Final.** Closes CVE-2026-47244 + CVE-2026-50560 (HTTP/2 DoS, gRPC hot path) and CVE-2026-50020 (conditional HTTP/1 smuggling). Convergence is build-local (all `io.netty:*` incl. transitive-only `netty-codec-http2`); nothing leaks into published POMs — consumer BOMs stay authoritative. Consumers on Quarkus should adopt BOM 3.33.2.1 (same Netty batch).
* io_uring transport evaluated (flag-gated PoC on `feat/iouring-eval`, NOT shipped): works in native but 5–6% slower than NIO on the typical small-message unary path; deferred to Quarkus 4 / Netty 4.2 (NATIVE-003). SPEC §13 note.
* Docs: SPEC §13 rewritten as the native-image consumer SoT (version matrix, server-provider + substitution workarounds pending ext-rpc 1.0.2, build recipe, checklist).
* Heterogeneously reviewed (codex r1 REQUEST-CHANGES -> fixes -> r2 APPROVE, 0 findings).

# 1.0.2, 2026-06-22

* Per-request server context migrated from a hand-rolled `ThreadLocal` to gRPC-native **`io.grpc.Context`** (`ServerContext` `SC_KEY`; attach/detach in `UnaryMethod`). Behavior-equivalent, no wire change; gRPC-managed scope, virtual-thread-friendly. Heterogeneously reviewed (codex).
* Virtual-thread cleanup: dropped Netty `FastThreadLocal` (`ServerContext`, `ClientContext`); `Es256Signature` now creates a `Signature` per call instead of a per-thread cache.
* **Agent-friendly P0** (ADR-0004): opt-in HTTP `/agent/discover` (web-only `ApiMeta`) + `/agent/invoke` endpoints; hidden services double-filtered, credential not bypassed. Auth/rate-limit are the gateway's responsibility.
* `extRpcVersion` -> 1.0.1 (depends on the `@ConfigMapping` / Quarkus 3.33-compatible ext libraries now on Central).
* Docs: SPEC JWT/JWKS auth + native-image reflection sections; ADR-0004.

# 1.0.1, 2026-06-20

* Trace propagation migrated from B3 multi-header to **W3C Trace Context** (`traceparent`), opaquely forwarded; `tracestate` + `x-request-id` carried; B3 (`x-b3-*`) no longer emitted or read (ADR-0003). Wire change vs 1.0.0 — sibling clients must adopt W3C for cross-service trace continuity.
* gRPC/Netty server executor runs on virtual threads (one named virtual thread per RPC; JDK 21, ADR-0002).
* Build: upgrade to Gradle 9.6.0 (wrapper checksum-pinned); jandex 2.0.0 -> 2.3.0; drop sonarqube plugin; migrate `gradle/upload.gradle` off the removed `Project.exec()` to `providers.exec`.

# 1.0.0 (Maven Central GA), 2026-06-20

* First general-availability release on Maven Central (group `tech.krpc`), promoted from `1.0.0.rc1`.
* Build toolchain: pin and track the official Gradle wrapper 8.14.5 (reproducible, checksum-pinned).
* Quarkus 3.15.2 -> 3.33.2 LTS (Gradle 8.14.5 / Gradle 9 compatible plugin line).
* grpc-java 1.74.0 -> 1.82.0; Netty unified to 4.1.133.Final across the whole runtime graph.
* Native: test-server native build on Mandrel 25 / JDK 25 (container build), language level 21.

# 1.0.2 2025-12-09

* 客户端注入bean使用全量命名
* quarkus/DTO自动反射到8层
* 支持`springboot` JIT模式发布服务


# 1.0.0 , 2023-05-05

* `javax.` -> `jakarta.`
* quarkus -> 3.0
* grpc -> 1.54.1

# 1.0.0 , 2020-12-02

* add client final message support

2020-07-21 GLS , publish online.

# 1.0.0 , 2020-06-30

* .net2.0 client publish

# 1.0.0 , 2020-06-20

* Dictionary key keep same , not camel



# 1.0.0 , 2020-05-06

* remove  Google.Api.CommonProtos
* shortter Property name of Outmessage


# 1.0.0 , 2020-04-24

* ci/cd ok



# 1.0.0-rc , 2020-04-20

* appsettings.json for Client Side

# 1.0.0-rc , 2020-04-15

* Deadline Set Support
* EnableRestCall in appsettings.json

# 1.0.0-rc , 2020-04-14

* ValueType Ok
* Can offer a Simple Rest Wrap for  Grpc

# 1.0.0-rc , 2020-04-07

* Headers of Context is ok
* Contract 1.0.0 is release

# 1.0.0-rc , 2020-04-01

* Plugin System is OK.
* PublishSingleFile --self-contained=false will small Mvc 4Mb/88Mb /  csproj 
* Plugin System with  AssemblyLoadContext 
  * It's recommended that shared dependencies should be loaded into AssemblyLoadContext.Default. This sharing is the common design pattern.
  * AssemblyCatalog ;var files = Directory.EnumerateFiles("DIR", "*.dll", SearchOption.TopDirectoryOnly);
  *   var assembiles = Directory.GetFiles(AppContext.BaseDirectory, "*.dll", SearchOption.TopDirectoryOnly)
            .Select(AssemblyLoadContext.Default.LoadFromAssemblyPath);
  * https://github.com/natemcmaster/DotNetCorePlugins
  * https://codetherapist.com/blog/netcore3-plugin-system/
  * https://medium.com/@mailbox.viksharma/resolve-dependencies-using-mef-and-built-in-ioc-container-of-asp-net-core-aae198cd38b6
  * https://cjansson.se/blog/post/creating-isolated-plugins-dotnetcore
  * DI/Log/Config https://github.com/ibebbs/Cogenity.Extensions
  * https://github.com/thinkabouthub/NugetyCore/wiki/Module-Discovery
  * Learn  Scan From  https://github.com/khellang/Scrutor
  * Learm From .net core 3.0  https://github.com/dapplo/Dapplo.Microsoft.Extensions.Hosting
  * https://github.com/thinkabouthub/NugetyCore
  * https://docs.microsoft.com/en-us/dotnet/core/dependency-loading/understanding-assemblyloadcontext
  * https://docs.microsoft.com/en-us/dotnet/core/tutorials/creating-app-with-plugin-support
  * https://github.com/grpc-ecosystem/grpc-gateway

// TODO


Method : cacheKey , Timeout
Helm Chart

* W3C Tracing  https://gist.github.com/lmolkova/6cd1f61f70dd45c0c61255039695cce8
* API Cache. Throw HashCode
* Simple .NET logging with fully-structured events https://serilog.net
* use message-pack to deir  https://github.com/neuecc/MessagePack-CSharp
* Support stream Call, Like https://github.com/Cysharp/MagicOnion 
* Integrations  yager 
  * https://github.com/Cysharp/MagicOnion#telemetry
  * https://github.com/open-telemetry/opentelemetry-dotnet#auto-collector-implementation-for-activitydiagnosticsource
  * https://github.com/Cysharp/MagicOnion/blob/master/src/MagicOnion.OpenTelemetry/MagicOnionCollector.cs#L306
  * Replace Swagger with 
  * https://github.com/grpc-swagger/grpc-swagger
  * https://github.com/mercari/grpc-http-proxy
* OpenTelemetry exporter, like Prometheus, StackDriver, Zipkin and others.
* GlobalStreamingHubFilters  only Server Side. using StreamingHub


* Hacks such as Domain sharding, resource inlining and image spriting will be counter-productive in an HTTP/2 world.
* HTTP/2 is not a replacement for push technologies such as WebSocket or SSE.
* HTTP/2 Push server can only be processed by browsers, not by applications，Additionally HTTP/2 is not a full duplex protocol so can only respond to requests (though possibly with more than one response thanks to Server Push). You say you only need this for client-server messaging so this may be less of a concern for you. In fact Websockets over HTTP/2 has been approved which will allow the HTTP/2 binary format to be used for websockets by wrapping websockets messages in the HTTP/2 Data frame. 
  
* Combining HTTP/2 and SSE provides efficient HTTP-based bidirectional communication.
* WebSocket will probably remain used but SSE and its EventSource API combined with the power of HTTP/2 will provide the same result in most use cases, just simpler.



* To enumerate all assemblies that the app is composed from, look at Microsoft.Extensions.DependencyModel. E.g. foreach (var l in Microsoft.Extensions.DependencyModel.DependencyContext.Default.RuntimeLibraries) Console.WriteLine(l.Name); will print names of all assemblies that the app is composed from. However, loading all assemblies that the app is composed from tends to scale poorly with size of the application and results in slow startup.



https://github.com/grpc/grpc/blob/master/doc/health-checking.md
 liveness 和 readiness path
  enum ServingStatus {
    UNKNOWN = 0;
    SERVING = 1;
    NOT_SERVING = 2;
  }
service Health {
  rpc Check(string service) returns (ServingStatus status);

  rpc Watch(string service) returns (stream ServingStatus status);
}

