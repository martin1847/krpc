# OTEL-001 — OTel Span Creation On KRPC Faces (Implementation Findings)

Owner: omp. Branch `feat/otel-interceptors` (from origin/dev @ 049e8ff). ADR-0006.
Date: 2026-07-16.

## What changed

Span creation joins the framework at KRPC's three self-owned faces, using the OpenTelemetry
**API only** (no SDK/exporter in core; ADR-0001 / NS-3).

| Area | File | Change |
| --- | --- | --- |
| Shared | `rpc-common/.../context/KrpcOtel.java` (new) | Flag (`rpc.otel.enabled`/`KRPC_OTEL`), `volatile OpenTelemetry` + `install(...)` (no global probing), installed-OTel tracer/propagator, gRPC `Metadata` getter/**overwriting** setter, rpc/http attribute keys. |
| gRPC server | `rpc-server/.../OtelServerInterceptor.java` (new) | Extract inbound W3C ctx → `SERVER` span → made current on every listener callback (incl. `onHalfClose`, where the handler runs on a virtual thread). |
| gRPC server wiring | `rpc-server/.../RpcServerBuilder.java` | `serverBuilder.intercept(new OtelServerInterceptor())` when enabled. |
| gRPC client | `rpc-client/.../OtelClientInterceptor.java` (new) | `CLIENT` span (child of current ctx) + W3C `traceparent` injection. |
| gRPC client wiring | `rpc-client/.../MethodCallProxyHandler.java` | Channel wrapped via `ClientInterceptors.intercept` when enabled; field widened `ManagedChannel`→`Channel`. |
| HTTP | `http-server/.../AbstractHttpHandler.java` | Extract W3C ctx from request headers → `SERVER` span around `handle(...)`, current on the handler's virtual thread. |
| Deps | `gradle.properties`, `rpc-common/build.gradle` | `otelVersion=1.49.0`; `api io.opentelemetry:opentelemetry-api` in rpc-common (transitive to server/client/http). |
| Docs | `ADR-0006` (new), `ADR-0003` (amended), `active-roadmap.md`, `docs/INDEX.md` | See ADR/roadmap sections. |

## Dependency decision (premise VERIFY)

- **Claim: krpc gRPC server accepts a standard `ServerInterceptor` / client a `ClientInterceptor`
  without forking grpc-java.** VERIFIED. Server: `ServerBuilder.intercept(...)` in
  `RpcServerBuilder.init`. Client: `ClientInterceptors.intercept(channel, ...)` in
  `MethodCallProxyHandler`. No grpc-java internals touched.
- **Claim: `opentelemetry-grpc-1.6` compatible with pinned grpc/JDK 21.** N/A — **rejected in
  favor of hand-rolling on `opentelemetry-api`**. Rationale: the instrumentation library is
  `-alpha` and drags `instrumentation-api` + `-incubator` + `semconv` into core. The RPC surface
  is small; hand-rolling keeps core to exactly `opentelemetry-api` (+ transitive
  `opentelemetry-context`), both stable, native-clean. rpc semantic attribute names are hardcoded
  in `KrpcOtel` to avoid the alpha `semconv` artifact. Resolution verified:
  `:rpc-common:dependencies` shows `opentelemetry-api:1.49.0 -> opentelemetry-context:1.49.0` and
  nothing else. Version pinned to the OTel SDK in the Quarkus 3.33 LTS BOM (1.49.0) for zero skew.
- **Claim: MDC forwarding (ADR-0003) and OTel propagation coexist without duplicate/conflicting
  headers.** VERIFIED by test (below). `KrpcOtel.METADATA_SETTER` overwrites (removeAll+put), so
  exactly one `traceparent` on the wire in both modes.

## Flag semantics

- `rpc.otel.enabled` (system property) or `KRPC_OTEL` (env). Default **ON**. Explicit `false`/`0`
  disables; property wins over env. Resolved once at class load → OFF makes interceptors
  byte-level absent (no per-call cost).
- Default-ON is safe: until an SDK is **installed** via `KrpcOtel.install(...)`, `KrpcOtel` is a
  no-op — no-op tracer, no-op propagator injects/extracts nothing. Zero spans, zero header
  changes, zero cost beyond a no-op call (NS-4 byte-identical wire). The no-op path was confirmed
  zero-behavior by the parity test and the native no-SDK smoke — no STOP-AND-REPORT triggered.

## Verified (actually run this session)

All via `gradle` (9.6.0) on Oracle GraalVM 25 (tests) / GraalVM 21 (native).

- **Full module suites green** (no existing test changed/skipped):
  `rpc-common: 25 tests, 0 failures` · `rpc-client: 23, 0` · `rpc-server: 59, 0` ·
  `http-server: 17, 0`. Total **124 tests, 0 failures**.
- **Propagation across a real in-process hop** — `OtelPropagationHopTest`
  (`inboundContextFlowsThroughClientAndServerSpans`, PASS): with the SDK test exporter, a root
  (inbound) span → `blockingUnaryCall` through `OtelClientInterceptor` + `OtelServerInterceptor`
  on a real in-process transport. Asserted and passing:
  - one trace id across root, CLIENT, SERVER spans;
  - parent chain root → CLIENT → SERVER (`SERVER.parentSpanId == CLIENT.spanId`,
    `CLIENT.parentSpanId == root.spanId`) — i.e. inbound `traceparent` → server span → (its)
    client span → outbound `traceparent`, one header, correct parents;
  - `rpc.system=grpc`, `rpc.service=krpc.test.Echo`, `rpc.method=echo` on the SERVER span;
  - the SERVER span is current inside the handler, which runs on a **virtual thread**.
- **No-SDK parity** — `OtelClientMdcParityTest` (3 PASS):
  - `noSdk_mdcTraceparent_forwardedAsSingleHeader`: no SDK + MDC `traceparent` → outbound carries
    exactly `[<the MDC value>]` (byte-identical to pre-OTEL ADR-0003).
  - `noSdk_noMdc_noTraceparent`: no SDK, no MDC → no `traceparent` emitted.
  - `sdkPresent_clientSpanTraceparent_supersedesStaleMdc_singleHeader`: SDK + a *stale* MDC
    `traceparent` → exactly one outbound `traceparent`, carrying the current trace id (not the
    stale one), whose span id is the CLIENT span (child of the current server span). Proves the
    overwrite-not-duplicate coexistence.
- **HTTP SERVER span** — `HttpOtelSpanTest` (2 PASS): real `HttpServer` + `HttpClient`. Inbound
  `traceparent` → SERVER span is its child (same trace id, `parentSpanId == inbound span id`),
  named after the handler path, `http.response.status_code` attribute set, span current on the
  handler's virtual thread; no inbound context → fresh root SERVER span.
- **KrpcOtel unit** — `KrpcOtelTest` (6 PASS): flag resolver matrix (default ON; `false`/`0`
  disables; prop wins over env) + metadata setter single-value overwrite + getter.
- **NS-3 gate (manual)**: `gradle :<m>:dependencies --configuration runtimeClasspath | grep -i
  opentelemetry` for `rpc-common`, `rpc-client`, `rpc-server`, `http-server`,
  `rpc-server-quarkus` shows ONLY `opentelemetry-api:1.49.0 -> opentelemetry-context:1.49.0`. The
  `opentelemetry-sdk|exporter|otlp` grep across all five is **empty**. No SDK/exporter in any core
  runtime classpath.
- **Native (NS-7)**: `examples:quickstart` native image built (GraalVM 21, host toolchain, `-x
  test`) — **BUILD SUCCESSFUL in 1m40s**, no reflection/build errors from the OTel surface. Booted
  the runner (`quickstart 1.1.0 native ... started in 0.027s`) and smoked: `GET /agent/discover`
  → 200, `GET /mcp` → 405, malformed `POST /agent/invoke` → 400, and a real gRPC call via `rpcurl`
  (`quickstart/Hello/hello`) → `code 0` with the echoed greeting — exercising the registered
  `OtelServerInterceptor` (no-op, no SDK) on the native gRPC path with zero behavior change.

## NOT verified

- **Full OTLP export to a real backend** (spans leaving the process to a collector) is not run
  locally — no OTel SDK/exporter ships in core by design (NS-3). This is now the ONLY deferred
  item; LH's iac probe covers deployed OTLP E2E. Everything else — span creation, parentage,
  cross-hop propagation, and the Quarkus SDK-present injection path — is proven locally (in-process
  SDK test exporter + the `examples:quickstart` `@QuarkusTest`, see Fixround r3).
- (r1 said no `@QuarkusTest` was added — superseded by r3, which adds the positive-injection
  container test in `examples:quickstart`.)
- Native run used **GraalVM 21** locally; CI uses **Mandrel 21**. Both are GraalVM-family JDK 21
  native-image; no Mandrel-specific path was exercised locally.
- `GeneralizeClient` (the generic rpcurl/introspection client path) is **not** instrumented — it
  is outside the typed cross-service chain scenario and out of this goal's scope.

## Remaining risks

- **Span end on client cancellation**: the CLIENT span ends in `onClose`; the SERVER span ends on
  the terminal listener event (`onComplete`/`onCancel`, guarded by an `AtomicBoolean`). Streaming
  is not a concern (KRPC is unary). If a future non-unary path is added, the terminal-event
  handling must be revisited.
- **`opentelemetry-api` version drift**: pinned to 1.49.0 (Quarkus 3.33 LTS). It is a plain `api`
  dep (not a published platform constraint), so a consumer BOM still wins the transitive version;
  the OTel API is stable within 1.x for the surface used (OpenTelemetry, Tracer/Span/SpanKind,
  Context/Scope, TextMap propagation, Attributes).
- **MDC still populated in parallel** (ADR-0003) for the log layout; this is intentional and
  tested to not double-emit `traceparent`. If MDC forwarding is ever removed, the no-SDK
  propagation guarantee would rest solely on OTel (which is a no-op without an SDK) — do not remove
  the MDC path without replacing that guarantee.

## FOR / NOT FOR boundaries

No module boundary crossed into NOT FOR. `rpc-common` gains a shared instrumentation helper (API
only); server/client/http-server gain their own face's interceptor. No telemetry backend, registry,
LB, or SDK entered core (ADR-0001 / NS-3 intact).

## Fixround r1 (codex: 2 blocking) — resolved

### B1 — no-SDK path provably free

- **Short-circuit added.** Each interceptor now calls `KrpcOtel.isNoop()` first and takes a fast
  path when no SDK is installed: `OtelServerInterceptor` returns `next.startCall(call, headers)`
  unwrapped, `OtelClientInterceptor` returns `next.newCall(...)` unwrapped, and
  `AbstractHttpHandler` gates the span on `OTEL_ENABLED && !isNoop()`. r1 wrapped every call and
  allocated a no-op span + `Context` + `Scope` + listener wrappers even with no SDK; r2 allocates
  nothing from OTel on that path.
- **Per-call, not build-time snapshot.** `isNoop()` = `GlobalOpenTelemetry.get().getTracerProvider()
  == TracerProvider.noop()` — an identity compare, allocation-free. Chosen per-call because Quarkus
  installs `GlobalOpenTelemetry` during startup with no ordering guarantee vs krpc's `@Startup`
  server/client construction; a build-time snapshot could latch "no-op" and silently disable
  tracing. Recorded in ADR-0006 ("No-op fast path").
- **Per-call allocation story (no SDK):** two field reads + one pointer compare per call; no object
  allocation, no header mutation, call proceeds untouched. ADR-0003 MDC forwarding is the only
  thing that may add a header (unchanged).
- **Proven on a real SDK-free classpath.** New Gradle source set `rpc-server:noSdkTest` whose
  runtimeClasspath carries `opentelemetry-api` only — no `opentelemetry-sdk`/`sdk-testing` (r1's
  "no-SDK" test ran with sdk-testing present + `resetForTest()`, which the reviewer correctly
  flagged as ≠ absent). `gradle :rpc-server:noSdkTest` → **7 tests, 0 failures**:
  - `Class.forName("io.opentelemetry.sdk.OpenTelemetrySdk")` and `...InMemorySpanExporter` both
    throw `ClassNotFoundException` (classpath really is SDK-free);
  - `KrpcOtel.isNoop()` == true;
  - client interceptor returns the delegate call **by identity** (unwrapped — short-circuit branch
    observed, not timing); server interceptor returns the delegate listener **by identity** (its
    stub `getMethodDescriptor()` throws if the span path runs — a live guard);
  - `MethodCallProxyHandler.makeCall` headers: no MDC → no `traceparent`; MDC set → exactly the MDC
    value (ADR-0003 byte parity), with logback supplying a real MDCAdapter.
- **Red-first proof:** temporarily forcing `isNoop()` to return `false` turned **4 of the 7**
  noSdkTest cases red (both identity fast-path checks + both `isNoop` checks); reverted.

### B2 — production-chain container test

- New `rpc-server/src/test/.../OtelProductionChainTest` (**2 tests, 0 failures**). Two real krpc
  netty servers on loopback ports via the **production registration path**
  (`RpcServerBuilder.Builder(APP).addService(...).build().startServer()` →
  `.intercept(OtelServerInterceptor)`), driven by real krpc clients
  (`new RpcClientFactory(APP, channel).get(iface)` → `MethodCallProxyHandler` →
  `OtelClientInterceptor` + `PropagateTraceCall`). Order-service A's handler calls Ledger-service B
  over the wire; in-process SDK exporter registered globally. This is the repo's existing
  container-test pattern (mirrors `test-server`'s `GrpcContextAuthIT`/`GracefulShutdownIT`), not a
  hand-built harness — it exercises the exact production wiring (only the Quarkus CDI wrapper
  `RpcServiceExpose`, which just calls `RpcServerBuilder`, is not booted).
  - `inboundToOrderToLedgerIsOneTraceWithCorrectParentage`: inbound root → CLIENT `/place` →
    SERVER-A `/place` → CLIENT `/record` → SERVER-B `/record`, one trace id, full parent chain
    (each `parentSpanId` asserted), and **exactly one `traceparent`** received on the A→B wire hop
    (counted from `ServerContext.current().getHeaders()` inside service B).
  - `exceptionPathEndsHopSpansWithErrorStatus`: service B throws; the SERVER-B and A→B CLIENT spans
    for `/boom` end with `StatusCode.ERROR`.
- The r1 `OtelPropagationHopTest` (raw in-process transport) is retained as a focused
  interceptor-level check (rpc attributes + VT-scope), no longer standing in as the container test.

### r2 verification summary

- `rpc-common:test` 25 · `rpc-client:test` 23 · `rpc-server:test` 61 · `rpc-server:noSdkTest` 7 ·
  `http-server:test` 17 = **133 tests, 0 failures**. `noSdkTest` is wired into `check`.
- NS-3 grep unchanged (no SDK/exporter in any core runtime classpath). Native path unaffected by
  the r2 changes (short-circuit is API-only, no new classes on the image path beyond `isNoop`).

## Fixround r2 (codex: GlobalOpenTelemetry.get() pins-no-op landmine) — resolved

### B1c — explicit injection, never `GlobalOpenTelemetry`

- **Landmine removed.** Core no longer calls `GlobalOpenTelemetry` anywhere. r1's `isNoop()` read
  `GlobalOpenTelemetry.get().getTracerProvider()`; on OTel API 1.49 `get()` on first read when unset
  does a synchronized init that pins the JVM-global to a no-op (or reflectively autoconfigures an
  SDK). A krpc call before the consumer's SDK registration would therefore permanently disable
  tracing AND make the consumer's later `buildAndRegisterGlobal()` throw. **The r1 "per-call get"
  design in the section above is superseded by this section.**
- **New design.** `KrpcOtel` holds a `volatile OpenTelemetry otel = OpenTelemetry.noop()` with a
  public `install(OpenTelemetry)` (idempotent-safe, null-ignored). `isNoop()` = `otel ==
  OpenTelemetry.noop()` (one volatile read + reference compare, allocation-free). `tracer()` /
  `propagator()` read the volatile. `grep GlobalOpenTelemetry` over all core `*/src/main/java` is
  empty (only doc prose in ADR-0006 explains why it is avoided).
- **Integration wiring (explicit install, no ambient magic):**
  - Quarkus `RpcServiceExpose` (`@Startup`) injects `Instance<OpenTelemetry>`; `isResolvable()` →
    `install(...)`, else stay no-op.
  - Spring `RpcServiceExposer#onApplicationEvent` and `RpcClientAutoConfigure#afterPropertiesSet`
    install via `getBeanProvider(...)`/`ObjectProvider.ifAvailable(...)`.
  - Plain-netty: documented explicit `KrpcOtel.install(openTelemetry)` (ADR-0006 + javadoc).
- **Empirical bean-availability caveat (honest):** the CDI `Instance<OpenTelemetry>` injection +
  `install()` wiring compiles and is exercised by construction, but I did **not** boot a full
  Quarkus app with `quarkus-opentelemetry` to observe `isResolvable()==true` at `@Startup`. The only
  Quarkus module here (`test-server`) boots MySQL/agroal under `@QuarkusTest`, which is not runnable
  in this environment (its ITs are deliberately plain, non-`@QuarkusTest`, for that reason). The
  install path is correct by the CDI contract (`@Startup` runs after container init; `Instance` is
  always injectable, `isResolvable()` reflects bean presence), and the production-chain test proves
  the interceptors + `install()` trace end-to-end. Deployed Quarkus verification remains LH's iac
  probe. NOT-verified is stated rather than faked.
- **Ordering regression test** (`OtelInstallOrderingTest`, rpc-server, 1 test): a call intercepted
  while no-op is unwrapped + emits no traceparent; after `install(sdk)` a call is wrapped, emits one
  traceparent, and exports a CLIENT span; and `GlobalOpenTelemetry.set(...)` afterward does **not**
  throw — proving core never pinned the global. Red-first: neutering `install()` turns it red.
- **noSdkTest** assertions already design-agnostic (isNoop true on a genuinely SDK-free classpath +
  unwrapped delegates) — still 7/7 green under the volatile design.

### r2 verification summary

- `rpc-common:test` 25 · `rpc-client:test` 23 · `rpc-server:test` 62 (+`OtelInstallOrderingTest`) ·
  `rpc-server:noSdkTest` 7 · `http-server:test` 17 = **134 tests, 0 failures**.
- `grep -rn GlobalOpenTelemetry rpc-*/src/main/java http-server/src/main/java` = **no matches**
  (core main is global-free; tests use it only to assert it stays unpinned).
- NS-3 unchanged (opentelemetry-api + opentelemetry-context only in core runtime). Native re-checked.

## Fixround r3 (codex: Quarkus SDK-present branch IS locally provable) — resolved

### B1d — positive-injection container proof in `examples:quickstart`

r2's "unrunnable locally" claim was wrong: `examples:quickstart` is a DB-free Quarkus consumer with
existing `@QuarkusTest` infra (no Agroal/JDBC). Added `OtelServerSpanQuarkusTest` there.

- **Test-scope only** (published example's production deps unchanged): `testImplementation`
  `quarkus-opentelemetry` + `opentelemetry-sdk-testing` + `rpc-client` + `grpc-stub`; a
  `@Produces @Singleton InMemorySpanExporter` bean; a `src/test/resources/application.properties`
  (merged with main) carrying the classloader config below. No `src/main` change to the example.
- **Proves, in one @QuarkusTest:** (1) `Instance<OpenTelemetry>.isResolvable()==true` — the same
  bean `RpcServiceExpose` injects at `@Startup`; (2) `KrpcOtel.isNoop()==false` — the startup hook
  actually ran `install(...)`; (3) a real krpc gRPC call (`quickstart/Hello/hello`, over loopback
  :50051) exports a SERVER span named `.../hello` with `rpc.system=grpc`, `rpc.method=hello`.
- **Split-classloader fix:** a gRPC client referenced from a `@QuarkusTest` class hits a Quarkus
  loader-constraint `LinkageError` (io.grpc / tech.krpc.internal / opentelemetry-api loaded by both
  the base and Quarkus loaders). Resolved by (a) moving all `io.grpc` usage into a CDI bean
  (`HelloGrpcCaller`, app loader) and (b) `quarkus.class-loading.parent-first-artifacts` for the
  grpc-core (non-netty) + krpc + otel-api/context + protobuf/guava/perfmark artifacts, collapsing
  them to one copy shared by both loaders (grpc-netty + io.netty stay app-loaded and see the
  parent's grpc-api). This is **test-only config**, not a runtime/product concern.
- **Red-first:** neutering the `RpcServiceExpose` install wiring (`if (false)`) turns the container
  test red (isNoop stays true → no SERVER span); reverted.
- The other three quickstart `@QuarkusTest`s (Agent/MCP, 3+5+1) still pass with OTel on the test
  classpath.

### r3 verification summary

- `rpc-common:test` 25 · `rpc-client:test` 23 · `rpc-server:test` 62 · `rpc-server:noSdkTest` 7 ·
  `http-server:test` 17 · `examples:quickstart:test` 10 (incl. the new container test) =
  **144 tests, 0 failures**.
- The Quarkus SDK-present injection branch is now proven locally. The only remaining deferral is
  full OTLP export to a real backend (LH iac probe).
