# OTEL-003 — Implementation & Findings (omp)

Branch `fix/otel-003` off `origin/dev @ 62cf205` (contains 1.1.1). Reviewer: codex. Commits LOCAL, no push.
Scope: otel/trace-propagation code + tests + this doc. Load: observability-standard.

---

## ✅ CASE CLOSED (2026-07-19) — 3/3 green in the field. Three real causes, all fixed.

OTEL-002/003 is **CLOSED**. The one-line-per-round narrative below is the historical investigation
(hypotheses raised and killed); this ledger is the durable truth. Field verification and the
mesh-tracing confirmation are recorded in two committed case exhibits (no secrets; hostnames absent):
`EVIDENCE-20260719-0049-iac-otel003-mesh-confirmed.md` and
`EVIDENCE-20260719-0112-iac-otel-fullcase-closed.md`.

### Root-cause ledger — three real causes, their deployed fixes, and evidence

| # | Real cause | Deployed fix (owner) | Evidence |
|---|---|---|---|
| 1 | **Legacy `OtelServerFilter`** (`@GlobalFilter`) coexisting with the built-in `OtelServerInterceptor` since 1.1.1 → **double SERVER span** per hop on the 5 edge servers; jdbc/CLIENT re-parented onto the legacy span. | Consumer (LH) **deleted** the legacy filter. | Rounds 2/4 (reproduced: `OtelDoubleServerSpanQuarkusTest`); kill-then-green: the ×2/double-SERVER symptom vanished with filter deletion alone. |
| 2 | **Dependency drift**: `rpc-server-quarkus:1.1.1 → ext-rpc:1.0.3` pinned **`rpc-client:1.0.3`** (pre-OTEL-001, no `OtelClientInterceptor`) → **no CLIENT span** while the 1.1.1 server exported SERVER spans. | **ext-rpc:1.0.5** published to Central (pins rpc-client 1.1.1) + **krpc PR #32**; consumer forced `rpc-client:1.1.1` and rebuilt. | Round 5 (POM/bytecode archaeology + version-fingerprint table). |
| 3 | **Infra mesh tracing**: an Istio ambient **waypoint (L7 Envoy)** with a `Telemetry` CR (opened 2026-07-16, 100% sampling, OpenObserve OTLP) **rewrote `traceparent` with a fresh Envoy span-id per hop**; Envoy's OTLP exporter carried no auth → OpenObserve **401-rejected** its spans → a bodiless (ghost) parent per hop, trace-id continuous. Also explains the rc1-era ghosts (when krpc had no CLIENT interceptor, the ghost id was Envoy's). | Infra seat **dismantled** the `Telemetry` CR (gitops `136ab04`, Argo prune confirmed); `meshConfig` provider left lazy. Reopen will ship an auth'd Envoy exporter as a separate project. | Exhibit `…-0049-…mesh-confirmed.md` (infra's own config archaeology + dismantle) → then 3/3 green. |

**Plus krpc's own real gain (this branch):** the OTEL-001/002 container tests were blind because
`opentelemetry-sdk-testing` hijacked OTel `LazyStorage` to a ThreadLocal `SettableContextStorage`, so
no test ran on the real `QuarkusContextStorage`. Fixed: dropped sdk-testing, pinned
`QuarkusContextStorage`, added a fail-closed active-storage guard + wire==CLIENT parity/chain tests.

### Final field verification — 3/3 (exhibit `…-0112-…fullcase-closed.md`)
- **prepay hop** (trace `88aa9ee4c1ed598252b47905640a3b6a`): order **CLIENT `f63566cd`** → payment
  **SERVER parent == `f63566cd`** — closed (only the front-end `traceparent` remote root remains, in-spec).
- **callback chain** (trace `804d52640a42fcd66eb4f0711dd43d8b`): webhook→payment→ledger, **37 spans,
  ZERO ghosts** — webhook ROOT → CLIENT → payment SERVER (parent matched) → CLIENT → ledger SERVER
  (parent matched) → double-entry jdbc tree.
- ① callback chain stitched · ② CLIENT bodies exported · ③ zero ghost parents = **3/3**.

**The proposed 1.1.2 "wire-id" krpc fix is WITHDRAWN** — krpc's injection was correct all along; the
wire pollution was the mesh, now removed. krpc's parity tests were right. No krpc code fix was needed
or shipped for the field defect; this branch ships only the test-harness correction + this case file.

> Historical honesty: earlier rounds below reached interim conclusions that were later **killed**.
> Each killed hypothesis is marked KILLED inline; read them as the investigation trail, not current truth.

Round result in one line: **`先量再改` bit hard. Building the maximal-fidelity staging assembly proved
the three field symptoms are NOT reproducible as a krpc code defect — the proposed header-authority
fix is disproven — while it DID surface the real reason every OTEL-001/002 test was green against a
red staging: the container tests ran on the wrong OpenTelemetry `ContextStorage`.** No speculative
krpc fix shipped (guardrail: 无复现不发投机修复). What shipped: a faithful test harness on the real
`QuarkusContextStorage`, red-first characterization tests that pin the actual boundary, and this
report with the wiring-diff matrix.

> **★ ROUND 7 — native gRPC path shows wire == CLIENT; root cause later CONFIRMED as the mesh (see
> CASE CLOSED, top).** Javaagent ruled out: all 8 consumer services are GraalVM native (infra grep: 0
> `-javaagent`/`JAVA_TOOL_OPTIONS`/initContainer/operator injection). Built the quickstart NATIVE image
> from this branch + a temp OTel probe (reverted): on the tested **native in-image gRPC CLIENT→SERVER**
> path, CLIENT/hello span=`3aca7a8862deb062` == SERVER/hello parent=`3aca7a8862deb062` → wire == CLIENT
> on that path (the probe eliminates native gRPC-client-injection divergence; it did NOT exercise the
> HTTP face or the ext-rpc synthetic bean). Combined with the field 3/3 closure, the wire pollution was
> NOT in-process at all: the injector was the **Istio ambient waypoint (Envoy)** — CONFIRMED by the
> infra seat's own config archaeology + dismantle-then-green (exhibits at top), not by an in-repo
> capture. Fix was mesh-side; the 1.1.2 wire-id krpc item is withdrawn.

> **[KILLED — javaagent structurally impossible (native binaries); true injector = infra mesh tracing, see CASE CLOSED]** Historical round-6 banner retained:
> **★ ROUND 6 — last defect: a SECOND CLIENT injector (double instrumentation).** After the consumer
> forced rpc-client:1.1.1 + deleted filters, 2.5/3 fixed; remaining: wire traceparent id ≠ exported
> CLIENT span-id (fresh bodiless id/hop, continuity intact). PROVEN: the reachable krpc 1.1.1 +
> ext-rpc 1.0.4 code satisfies wire == CLIENT (OTel `removeAll+put` wins as the inner/last writer;
> parity + chain tests green; ext-rpc adds no injector; the 1.0.4↔1.1.1 mix is API-compatible). So the
> field needs a SECOND, DEEPER CLIENT injector that writes after krpc's `super.start` — prime suspect
> the **OTel Java agent's gRPC instrumentation** (the consumer's classpath has no grpc-instrumentation
> lib). CLIENT analog of the double-SERVER filter. `OtelClientDoubleInjectionTest` reproduces the exact
> signature; no krpc-only order can beat a deeper injector. Fix = single CLIENT instrumentation
> (disable the agent's grpc, or KRPC_OTEL). Decisive artifact: the exported CLIENT span's scope +
> whether the OTel agent runs. No speculative krpc code shipped. Details: ROUND 6 (end).

> **★ ROUND 5 — SOLVED (root cause).** Two independent edge-server defects, now both explained:
> (1) **the double SERVER span** = the legacy `OtelServerFilter` coexisting with the built-in
> interceptor (rounds 2/4 — delete the filter); (2) **the missing CLIENT bodies + ghost parents** =
> a **dependency drift**: `rpc-server-quarkus:1.1.1 → ext-rpc:1.0.3` pins **`rpc-client:1.0.3`**
> (pre-OTEL-001, **no `OtelClientInterceptor`**), while `rpc-server`/`rpc-common` are 1.1.1. So the
> server exports SERVER spans but the OLD client creates NO CLIENT span and forwards the inbound
> `traceparent` verbatim → distinct upstream-injected ghost per edge server, continuity intact,
> works with 1.1.1 servers. Fingerprint: is `tech/krpc/client/OtelClientInterceptor.class` present in
> the deployed `rpc-client`? Absent ⇒ drift. Fix = force `rpc-client:1.1.1`. Full evidence + version
> table in the ROUND 5 section (end).

> **[KILLED — see CASE CLOSED ledger]** (partial): the double-SERVER span + CLIENT re-parenting +
> "DELETE the legacy filter" ruling below STAND (ledger cause #1). But the **leak → ghost-parent =
> field ② "exactly"** attribution was KILLED — the field's ghost/missing-CLIENT was the dependency
> drift + the mesh waypoint (Envoy), not a never-ended filter span. Historical round-2 banner:
> **ROUND 2 UPDATE (consumer evidence arrived).** The STOP-AND-REPORT questions were answered:
> outbound is sync-inline (no executeBlocking), and the prime suspect is a legacy consumer
> `OtelServerFilter` (`@GlobalFilter @Unremovable implements ServerFilter`) coexisting with the
> built-in `OtelServerInterceptor` since 1.1.1. **Reproduced** in the real-storage assembly: double
> SERVER spans, the outbound CLIENT mis-parented to the legacy filter span, and — with the classic
> self-help leak (filter never `end()`s its span) — **ghost parent + traceId continuity intact
> (field ②) exactly**. Ruling: the consumer must DELETE the legacy filter; no krpc code change is
> warranted. Full round-2 analysis, migration text, and defensive-change evaluation are in the
> "ROUND 2" section at the end of this doc. The round-1 sections below stand (storage-blindness
> finding, header-authority disproof, item ① separate cause).

---

## TL;DR — the wiring diff that made it (not) reproduce (THE key knowledge)

Every earlier OTel container test (`OtelServerSpanQuarkusTest`, and the OTEL-001 quickstart proof)
put **`io.opentelemetry:opentelemetry-sdk-testing`** on the test classpath to get `InMemorySpanExporter`.
That artifact registers an SPI:

```
META-INF/services/io.opentelemetry.context.ContextStorageProvider
  = io.opentelemetry.sdk.testing.context.SettableContextStorageProvider
```

OTel's `io.opentelemetry.context.LazyStorage.createStorage(...)` **short-circuits to
`SettableContextStorageProvider` the instant it appears among the `ServiceLoader` providers —
before the `-Dio.opentelemetry.context.contextStorageProvider` property or any other provider is
consulted** (verified by decompiling `opentelemetry-context-1.49.0`, `LazyStorage.class`). So the
active storage in the tests was:

```
io.opentelemetry.sdk.testing.context.SettableContextStorageProvider$SettableContextStorage   (a ThreadLocal storage)
```

A **real Quarkus consumer** has no sdk-testing on the classpath, so the same `LazyStorage` resolves
the quarkus-opentelemetry SPI:

```
META-INF/services/io.opentelemetry.context.ContextStorageProvider
  = io.quarkus.opentelemetry.runtime.OpenTelemetryContextStorageProvider   -> QuarkusContextStorage.INSTANCE
```

`QuarkusContextStorage` is **dual-mode** (decompiled `quarkus-opentelemetry-3.33.2`): on a thread
carrying a Vert.x *duplicated context* it stores/reads the OTel context in the **Vert.x local**;
otherwise it delegates to a ThreadLocal **fallback** (`MDCEnabledContextStorage` → `ContextStorage.defaultStorage()`).

krpc runs its gRPC/HTTP handlers on **its own virtual-thread executor** (ADR-0002), which is *not* a
Vert.x context — so under `QuarkusContextStorage` krpc's SERVER span always lands in the **ThreadLocal
fallback**. That asymmetry is the entire ballgame, and it is invisible to any test that runs on a
plain ThreadLocal storage (which is exactly what sdk-testing forced). **The bug class lives in the
storage the tests never used.**

Proof it was wrong before / right now — `OtelClientChainQuarkusTest.activeContextStorageIsQuarkus()`
prints/asserts the live storage class:
- before (sdk-testing on cp): `...SettableContextStorageProvider$SettableContextStorage`
- after (sdk-testing dropped + provider pinned): `io.quarkus.opentelemetry.runtime.QuarkusContextStorage`

---

## What actually happens under the real QuarkusContextStorage (measured, not argued)

Assembly: `examples/quickstart` @QuarkusTest, `quarkus-opentelemetry` CDI SDK, **BatchSpanProcessor**
(Quarkus default), a `RecordingSpanExporter` (opentelemetry-sdk only — no sdk-testing), a second krpc
service `Chain` whose handler makes a real outbound krpc call to `Hello` over the wire (loopback
:50051). Chain under test: `CLIENT/chain → SERVER/chain → CLIENT/hello → SERVER/hello`.

| Outbound execution topology | OTel ctx at outbound | MDC at outbound | Result |
|---|---|---|---|
| **plain** — synchronous on krpc handler VT | SERVER span (ThreadLocal fallback) | inbound traceparent | **one trace, correct parentage, CLIENT/hello exported** ✅ |
| **managed** — MicroProfile `ManagedExecutor` (Quarkus `@Blocking`/Mutiny worker) | SERVER span (captured+restored) | *not* propagated | **one trace, correct parentage** ✅ |
| **vertx** — raw `executeBlocking` on a fresh duplicated context, no capture | **invalid/root** (`Span.current().isValid()==false`) | **null** | **CLIENT/hello = orphan ROOT in a NEW trace** ❌ |

Measured probe on the raw-hop worker: `thread=executor-thread-1 onDupCtx=true mdcTraceparent=null
otelCurrentValid=false`. Neither channel (OTel context nor MDC) crosses an un-instrumented hop.

Tests encoding this (all green): `OtelClientChainQuarkusTest`
- `activeContextStorageIsQuarkus` — the wiring guard.
- `syncPath_oneTraceWithClientSpanExported` — pure krpc path correct under real storage + BatchSpanProcessor.
- `managedExecutorPath_preservesTraceAcrossThreadHop` — correct propagation keeps the trace.
- `rawVertxHop_breaksTrace_consumerResponsibility` — RED-first boundary: raw hop orphans (asserts the break).
- `spansAreExportedExactlyOnce` — finding #4 guard.

---

## Findings against the field report (iac, OpenObserve 2026-07-17)

### Path A — interceptor lifecycle: NOT the defect
- `OtelClientInterceptor` is wrapped on the channel from `MethodCallProxyHandler:64` gated on
  `KrpcOtel.enabled()` (a `static final`, ON by default) — independent of install *timing*, so a
  proxy built before `KrpcOtel.install()` still traces (the per-call `isNoop()` check at
  `OtelClientInterceptor:38` is dynamic). Verified: install runs at `RpcServiceExpose:105-107`
  (`@PostConstruct`), before the server starts.
- The CLIENT span ends on the terminal path in all cases: `TracingClientCallListener.onClose`
  (`OtelClientInterceptor:90-99`) calls `span.end()` unconditionally; the exception path is covered
  green by `OtelProductionChainTest.exceptionPathEndsHopSpansWithErrorStatus`. The
  `PropagateTraceCall`(outer)→`TracingClientCall`(inner) wrapping in `MethodCallProxyHandler.makeCall:136-143`
  delivers `onClose` to the tracing listener (measured: CLIENT/hello exported on both sync and
  managed paths). No leak on any reachable path.

### Path B — header authority: the proposed fix is DISPROVEN
Proposed fix was "SDK present ⇒ OTel injector is the ONLY traceparent writer; `PropagateTraceCall`
defers entirely." Evidence against shipping it:
1. `PropagateTraceCall` is the **outer** call and runs `start()` first (`PropagateTraceCall:39-47`);
   the inner `TracingClientCall.start` injects **after** with `KrpcOtel.METADATA_SETTER` which
   `removeAll`+`put` (`KrpcOtel:145-152`). **OTel already wins** the single wire traceparent —
   confirmed by `KrpcOtelTest`, `OtelClientMdcParityTest`, and the green sync chain here.
   `PropagateTraceCall` never overwrites the OTel value, so it cannot be the source of a "hand-built
   ghost parent."
2. The ghost/orphan the field sees is not a header-ordering problem: on the un-instrumented hop MDC
   is **null**, so `PropagateTraceCall` is never even constructed. Making OTel the sole writer would
   *remove the MDC continuity fallback* and make the no-context orphan strictly worse.
3. The downstream parent is a real (if orphaned) OTel CLIENT id, not a hand-built MDC id — so it is
   not a header-ordering problem. **(The header-authority disproof above stands and is current: do not
   ship it.) [KILLED — see CASE CLOSED ledger]**: the round-1 corollary that "the defect is consumer
   context visibility/drop" was superseded — the field defect was the mesh waypoint (Envoy) + the
   dependency drift + the legacy filter, none of which is an in-process context-visibility bug.

**Recommendation: do NOT ship the header-authority change.** It targets a mechanism the evidence
rules out and would regress the no-SDK/lost-context continuity fallback.

### Item 3 (HTTP orphan root / tree in another trace): reproduced mechanism = same storage asymmetry
> **[KILLED — see CASE CLOSED ledger]**: the round-1 "HTTP orphan = Vert.x/storage-asymmetry context
> drop" mechanism was a lab repro, not the field cause. The field's webhook→payment→ledger break was
> the mesh waypoint (Envoy) rewriting `traceparent`; after the `Telemetry` CR was removed the callback
> chain is stitched (trace `804d5264…`, 37 spans, zero ghosts — CASE CLOSED). Historical hypothesis.
The HTTP face (`AbstractHttpHandler:190-262`) creates+scopes the SERVER span on `HANDLER_VT` (krpc's
own VT, ThreadLocal fallback). If the consumer's domain code (`payment.callback → ledger.settle`)
runs on a Vert.x context without capturing that context, it reads the empty Vert.x local → new-trace
root. Exactly the `rawVertxHop` result. OTEL-002 Fix1 (MDC binding) could not fix it because an
external webhook has no inbound traceparent to forward, and MDC does not cross the hop anyway.

### Finding #4 (duplicate span_id ×2): lab-reproduced failure mode — KILLED for this field case
> **KILLED as the field cause.** The consumer audit found **zero programmatic SDK/processor/exporter
> beans** (see "no programmatic SdkTracerProvider/Processor/Exporter beans", below) and the ×2 symptom
> **vanished with the dependency/filter correction alone — no exporter-wiring change** (final infra
> note + the double-SERVER-span deletion). So duplicate exporter/BSP registration was NOT the field's
> ×2 (the field ×2 was the double-SERVER-span filter + the mesh per-hop rewrite). Retained below only
> as a reproduced lab failure mode + regression guard (`spansAreExportedExactlyOnce`).
Exposing the span exporter under **two CDI bean types** (`@Produces RecordingSpanExporter` AND
`@Produces SpanExporter` of the same instance) makes Quarkus register it in the BatchSpanProcessor
twice → every span exported ×2 (the field's `DataSource.getConnection ×2` signature). Guarded in the
lab by `spansAreExportedExactlyOnce`. This was **hypothesized** as a consumer SDK/exporter
registration bug — **[KILLED — see CASE CLOSED ledger]**: the consumer audit found no such duplicate
wiring and the ×2 cleared with the dependency/filter fix alone (the field ×2 was the double-SERVER
filter + the mesh per-hop rewrite). Retained only as a reproduced lab failure mode + regression guard.

---

## Root-cause conclusion
> **[KILLED — see CASE CLOSED ledger]**: this round-1 conclusion (staging RED = a consumer
> async-context-drop, compounded by the ×2 double-export) was superseded. The "no reproducible krpc
> code defect" finding stands, but the actual field causes were dependency drift + the legacy SERVER
> filter + the mesh waypoint (Envoy) — not an async-context drop and not double-export. Read below as
> the historical round-1 hypothesis.

There is **no reproducible krpc code defect** behind the three symptoms in a maximal-fidelity
assembly (real `QuarkusContextStorage` + BatchSpanProcessor + a real Quarkus consumer):
- the synchronous krpc path is correct;
- a consumer that propagates context correctly (`ManagedExecutor`/`@Blocking`/Mutiny) is correct;
- the trace only breaks when the **consumer** runs the outbound call / domain span on an execution
  context **without propagating** the caller's OpenTelemetry context — a documented consumer
  responsibility (OTEL-002 R1-8, and the observability-standard "orphan span trap").

The staging RED is consistent with a consumer whose outbound/reactive path drops context (impl+jdbc
stay in-trace because they run synchronously on the krpc VT; only the async outbound diverges),
compounded by the ×2 double-export SDK-wiring bug. krpc is backend-agnostic (opentelemetry-api +
-context only, NS-3) and cannot bridge an arbitrary consumer thread hop without a Vert.x dependency.

This CONTRADICTS the round premise (in-process krpc defect + header-authority fix). It is raised with
evidence per the escalation duty; see STOP-AND-REPORT below for what would close the gap.

---

## Why OTEL-002's tests missed this (honest)

1. **Wrong ContextStorage.** OTEL-002/001 container proofs used `opentelemetry-sdk-testing`, which
   hijacks OTel `LazyStorage` to `SettableContextStorageProvider` (ThreadLocal). No test ever ran on
   `QuarkusContextStorage`, so the krpc-VT-vs-Vert.x-context asymmetry was structurally unobservable.
2. **No handler-originated CLIENT span in a container.** `OtelServerSpanQuarkusTest` only asserted a
   SERVER span. The CLIENT-span origin (a handler making an outbound call) was only tested in-process
   (`OtelProductionChainTest`, `GhostClientSpanTest`) with the default/SimpleSpanProcessor storage —
   again never `QuarkusContextStorage`, never a consumer thread hop.
3. **OTEL-002 explicitly deferred Fix 2 (ghost) to "the export/flush boundary, gated on staging."**
   Field evidence #1 (same process, same batch window, only CLIENT missing) refutes the
   export-boundary hypothesis (correct: the export channel was innocent). **[KILLED — see CASE CLOSED
   ledger]**: the round-1 corollary that "the real axis was in-process context *visibility* across
   storages" was superseded — the ContextStorage-blindness finding explains only why the *tests* were
   green; the *field* defect was the mesh waypoint (Envoy) + dependency drift + the legacy filter.

---

## Deliverables in this branch

Test-scope only (no main-source change → **NS-7 native rebuild not required**; NS-3/NS-4 untouched):
- `examples/quickstart/build.gradle` — drop `opentelemetry-sdk-testing`; pin
  `-Dio.opentelemetry.context.contextStorageProvider=...OpenTelemetryContextStorageProvider` for tests.
- `RecordingSpanExporter` (opentelemetry-sdk only) replaces `InMemorySpanExporter`; single-bean producer.
- `ChainService`/`ChainServiceImpl`/`ChainGrpcCaller` — a handler-originated outbound krpc call with
  plain/managed/vertx topologies.
- `OtelClientChainQuarkusTest` — storage guard + 4 characterization tests (red-first on the boundary).
- `OtelServerSpanQuarkusTest` — migrated to `RecordingSpanExporter` (now runs on QuarkusContextStorage).

Verification (all green): `:examples:quickstart:test` (incl. the new suite + migrated OTEL-001 proof),
`:rpc-common:test`, `:rpc-client:test`, `:rpc-server:test`, `:rpc-server:noSdkTest`, `:http-server:test`.

---

## STOP-AND-REPORT — what would close the gap (historical — superseded; questions answered in rounds 5-7, see CASE CLOSED)
> **[KILLED — see CASE CLOSED ledger]**: these round-1 open questions were all answered (rounds 2/5 +
> the infra exhibits). The gap did not close via a consumer async-context bug or the ×2 double-export;
> it closed via dependency drift + the legacy filter + the mesh waypoint. Historical ask, retained.

A maximal-fidelity in-repo assembly does not reproduce a krpc defect. To convert the staging RED into
a fix (or confirm it is a consumer bug), need from the consumer for the reported trace (e9af6ce0…):
1. **How the outbound krpc call is dispatched** — is it synchronous on the krpc handler thread, or
   handed to a reactive/`Uni`/`CompletableFuture`/custom executor? Is that executor context-propagating
   (`ManagedExecutor`/`@Blocking`) or raw?
2. **The SDK/exporter wiring** — is any exporter/processor bean exposed under multiple types, or a
   programmatic `SdkTracerProvider` alongside the CDI one? (root of the ×2 double-export).
3. **The sampler** — `parentBased(...)` vs independent/tail — needed to interpret "CLIENT missing but
   downstream sampled".
4. Ideally the consumer's minimal reproducer of the impl+CLIENT path; dropping it into
   `ChainServiceImpl` here will make it RED, at which point the fix (consumer propagation, or a krpc
   affordance if one is warranted) follows.

---

# ROUND 2 — legacy `OtelServerFilter` × built-in interceptor (reproduced)

## Consumer facts that redirected the investigation
- Outbound is **sync-inline** on the krpc dispatch thread (no `@Blocking`/executeBlocking/custom
  executors) — so round-1's Vert.x-hop path does NOT apply to the field cases.
- Five edge servers (order/catalog/user/technician/merchant) each carry a legacy
  `OtelServerFilter` (`@GlobalFilter @Unremovable implements ServerFilter`), pre-OTEL-001 trace
  self-help: extract inbound ctx + start a SERVER span + `makeCurrent` + wrap `next.Invoke`.
- Internal services (payment/ledger) have NO such filter and were ALWAYS clean — reverse confirmation.
- Wiring: `quarkus.otel.traces.exporter=cdi`, propagators `tracecontext,baggage`, BSP delay 1s,
  sampler `parentbased_always_on`, zero programmatic SDK/processor/exporter beans, zero env overrides.

## Reconstruction (test scope)
`LegacyOtelServerFilter` (`@GlobalFilter @Unremovable @Startup`, gated by
`otel003.legacy-filter.enabled` / `.leak` so the other quickstart tests are unaffected) mirrors the
described filter: `@Inject OpenTelemetry` → `propagator.extract(inbound)` → SERVER span (name
`Service/method`) → `makeCurrent` → `next.invoke`. Installed in the real `QuarkusContextStorage`
assembly next to the built-in `OtelServerInterceptor`. **Faithfulness caveat (verbatim source still
pending):** modelled with the CDI `OpenTelemetry` bean (idiomatic Quarkus self-help); the span-name
string and the `end()`/leak lifecycle are the only guessed details, and the `.leak` flag lets us test
both lifecycles.

## Measured mechanism (dumps, single hop, all one trace = continuity intact)

Filter installed, span correctly ended:
```
CLIENT -quickstart/Chain/chain  span=abcd parent=0000            scope=tech.krpc          (test caller)
SERVER  ChainService/chain      span=5f44 parent=abcd            scope=legacy-...filter   (S2 filter)
SERVER -quickstart/Chain/chain  span=d26c parent=abcd            scope=tech.krpc          (S1 interceptor, CHILDLESS)
CLIENT  quickstart/Hello/hello  span=6af9 parent=5f44 <── legacy scope=tech.krpc          (outbound parents to S2!)
SERVER  ChainService/hello      span=6628 parent=6af9            scope=legacy-...filter
SERVER  quickstart/Hello/hello  span=62c9 parent=6af9            scope=tech.krpc
```
Filter installed **and leaking** (never `end()`s its span):
```
SERVER -quickstart/Chain/chain  span=c248 parent=abcd  scope=tech.krpc
CLIENT  quickstart/Hello/hello  span=f244 parent=1288  ← 1288 has NO exported span object = GHOST
SERVER  quickstart/Hello/hello  span=2ed8 parent=f244  scope=tech.krpc
```

Precise root cause of the interaction:
> **[KILLED — see CASE CLOSED ledger]** (partial): items 1-3 below (two SERVER spans + CLIENT
> re-parenting + continuity) STAND = ledger cause #1. Item 4 (leaked-filter-span → ghost parent =
> field ②) was KILLED: the field's ghost/missing-CLIENT was the dependency drift + the mesh waypoint
> (Envoy), not a never-ended filter span. Read item 4 as the historical round-2 hypothesis.
1. **Two SERVER spans per hop.** The built-in interceptor (scope `tech.krpc`) and the legacy filter
   (scope `legacy-consumer-otel-filter`) each `extract(inbound)`+start a SERVER span. Both are
   children of the inbound remote span → **siblings**, not nested. Different names
   (`-quickstart/Chain/chain` vs `ChainService/chain`) so a backend won't auto-dedupe them.
2. **Who is current / who parents the CLIENT.** The interceptor makes S1 current on the listener
   callback; the filter then makes S2 current *inside* the handler dispatch (innermost). So at the
   outbound call site `Context.current() == S2` → **the CLIENT span parents to the LEGACY filter
   span, and the framework interceptor span S1 is left childless.**
3. **Who injects what.** krpc's `OtelClientInterceptor` still starts a real CLIENT span (child of S2)
   and injects ITS id → downstream SERVER parents to the CLIENT. traceId is S2's (= inbound) → **
   continuity always intact.**
4. **Why a span "never exports while siblings do" (field ②).** The classic self-help bug: the legacy
   filter `makeCurrent`s S2 but never `end()`s it (or ends only on the success branch). An OTel span
   only exports on `end()`, so S2 never reaches the BatchSpanProcessor → it is the **ghost**. Its
   children (impl, jdbc, the CLIENT) all export normally but point at a parent id with no span object
   → "ghost parent, traceId continuity intact". Everything else in the batch window exports; only the
   leaked filter SERVER span is absent.

## Mapping to the exact field labels (honest)
> **[KILLED — see CASE CLOSED ledger]**: the **double-SERVER span + CLIENT re-parenting** mechanism
> below stands (that was real hygiene debt = ledger cause #1). But the **leak → ghost-parent = field
> ②** attribution was KILLED: the field's ghost/missing-CLIENT was the dependency drift (`rpc-client:
> 1.0.3`, no CLIENT interceptor) + the mesh waypoint (Envoy) rewriting `traceparent`, not a
> never-ended filter span. Read the leak→② mapping below as the historical round-2 hypothesis.
- **Field ② "ghost parent + continuity intact"**: reproduced exactly (leak dump above).
- **Field ① "missing CLIENT body"**: the reproduced ghost is the leaked *filter SERVER* span; the
  krpc CLIENT span itself always `end()`s in `onClose` and exports. Whether the consumer's OpenObserve
  view labels the ghost as the "missing CLIENT" for a hop depends on (a) the verbatim filter's exact
  `end()` semantics and (b) span-name grouping across the 5-service cascade (each edge server leaks
  its own S2, so every hop contributes a ghost parent). This residual labeling is the one item still
  pending the verbatim filter source; the driving mechanism (double-span + mis-parent + leak) is
  reproduced and sufficient to explain the family of symptoms.

## Item ① (HTTP webhook orphan root) — separate cause, confirmed
> **[KILLED — see CASE CLOSED ledger]**: the round-1 attribution (consumer domain-code context gap /
> propagator discrepancy) was superseded. The webhook orphan was the mesh waypoint (Envoy); after the
> `Telemetry` CR was removed the callback chain is stitched (CASE CLOSED). "Not a krpc defect" stands;
> the specific cause below is historical.
The legacy filter is a gRPC/`invokeWeb` `ServerFilter`: it runs only inside
`UnaryMethod.invoke`/`invokeWeb` (`filterChain.invoke`). The consumer's webhook "extends
`AbstractPostHandler`", a free-form POST handler that `AbstractHttpHandler` invokes directly on
`HANDLER_VT` — it never enters the `ServerFilter` chain. So the filter does NOT wrap the webhook, and
"AbstractPostHandler sync path was measured correct WITHOUT the filter" holds. Item ① therefore has a
separate cause (round-1: the HTTP pure path is correct; a webhook orphan needs a consumer context gap
in the domain code, or the noted propagator discrepancy — javadoc says b3multi, config is
`tracecontext,baggage` only). Not a krpc defect on the reachable path.

## Double-export ×2 (field #4) — stays separate
> **KILLED as the field cause (see CASE CLOSED).** Zero programmatic SDK/processor/exporter beans in
> the consumer + the ×2 symptom cleared with the dependency/filter fix alone (no exporter-wiring
> change). Kept as a lab failure mode + regression guard only.
The clean-lifecycle double-span dump shows 6 DISTINCT spans (no ×2). The legacy filter creates spans;
it does not register a processor/exporter, so it does NOT cause the ×2. Reproduced independently in
round 1: exposing a `SpanExporter` under multiple CDI bean types registers it in the
BatchSpanProcessor twice → every span exported ×2. This was hypothesized as a consumer SDK-wiring bug;
**[KILLED — see CASE CLOSED ledger]** as the field cause (the consumer had no such wiring; the ×2
cleared with the dependency/filter fix). Retained as a lab failure mode + guard only.

## Deliverable (a) — consumer migration ruling: DELETE the legacy filter
> **✔ ROUND 4 — discriminator resolved: DELETE stands (double-span confirmed); NOT the ② fix.** The
> field trace shows BOTH SERVER spans exported (built-in `tech.krpc`/grpc + legacy `krpc`), so 2a is
> DEAD and the export pipeline is healthy. Double-span pollution is CONFIRMED by the field ⇒ deleting
> the legacy filter is correct and safe (it does NOT remove the only exported SERVER span). BUT the
> field's missing-CLIENT (②) is NOT caused by the filter (it's well-behaved), NOT 2a, and NOT the
> ext-rpc path — deletion is hygiene, not the ② fix. ② remains OPEN (see ROUND 4).
> **[KILLED — see CASE CLOSED ledger]**: "② remains OPEN" was the round-4 status; ② is now RESOLVED
> (dependency drift + mesh waypoint). The "delete the filter = hygiene, not the ② fix" ruling stands.
**Yes — delete it.** Since 1.1.1 the built-in `OtelServerInterceptor` performs exactly the filter's
job (extract inbound W3C context → SERVER span → make current across the handler thread) and is the
authoritative, single source. The legacy filter is now pure harm: a duplicate sibling SERVER span per
hop, the outbound CLIENT re-parented onto the legacy span (framework span childless), and — given its
self-help lifecycle — ghost parents when it leaks. Internal filter-free services were always clean.

SPEC-CONSUMER migration line (one line):
> **OTEL/1.1.1:** delete any hand-written `OtelServerFilter`/trace `@GlobalFilter` — krpc's built-in
> `OtelServerInterceptor` now creates the inbound SERVER span; a coexisting filter double-spans and
> re-parents outbound CLIENT spans onto the (possibly leaked → ghost) legacy span.

## Deliverable (b) — does krpc need a defensive change? NO (argued)
Evaluated options and rejected:
- *Built-in interceptor detects/warns on a double SERVER span.* It cannot: the interceptor runs FIRST
  (gRPC interceptor layer); the consumer's filter runs LATER (handler-dispatch filter chain), so at
  interceptor time there is nothing to detect, and at filter time krpc does not mediate the consumer's
  own OTel `spanBuilder` calls. There is no reliable signal that distinguishes a duplicate self-help
  SERVER span from a legitimate nested SERVER span (e.g. a genuine sub-operation).
- *Make the interceptor "tolerate an outer filter".* There is nothing to tolerate — both spans are
  valid OTel spans; krpc cannot stop a `@GlobalFilter` from calling `tracer.spanBuilder(...)`.
- Cost/benefit: any heuristic (e.g. warn when a `@GlobalFilter` class name matches trace patterns) is
  fragile, false-positive-prone, and adds always-on cost for a one-time consumer migration. The
  correct, cheap, precise fix is consumer-side deletion + the SPEC-CONSUMER line above.
- Guardrail honored: no speculative krpc code. The repro flips green by DELETING the filter — i.e. the
  filter-OFF assembly (`OtelClientChainQuarkusTest`, single SERVER span, correct parentage, no ghost)
  IS the fixed state; the filter-ON assembly (`OtelDoubleServerSpanQuarkusTest` /
  `OtelLegacyFilterGhostQuarkusTest`) IS the reproduced defect.

## Round-2 tests (all green; red-first evidence = the dumps above + the filter-OFF contrast)
- `LegacyOtelServerFilter` (gated), `OtelDoubleServerSpanQuarkusTest` (double span + CLIENT parents to
  the legacy span + framework span childless), `OtelLegacyFilterGhostQuarkusTest` (ghost parent +
  continuity intact on leak). Filter-OFF `OtelClientChainQuarkusTest` remains the clean baseline.
- No main-source change → NS-7 native rebuild not required; NS-3/NS-4 untouched. All quickstart tests
  + `:rpc-common/:rpc-client/:rpc-server(+noSdkTest)/:http-server` suites green.

---

# ROUND 3 — verbatim filter (well-behaved) + the install-instance mechanism

## The verbatim source changes the picture
`CONSUMER-FILTER-SOURCE.md` (order-server, 91 lines) is **well-behaved**: it `span.end()`s in
`finally`, injects the CDI `OpenTelemetry` (consumer confirms filter + interceptor share one
SDK/exporter), extracts with a lower-casing Metadata getter, tracer scope `order-server`. So the
round-2 *leak* ghost is NOT the field artifact. `LegacyOtelServerFilter` was swapped to the verbatim
logic (imports/package + test gating only).

## Step 1 — does the well-behaved filter reproduce ②? NO.
`OtelDoubleServerSpanQuarkusTest` (verbatim, filter on): two SERVER spans per hop — framework
(`tech.krpc`, `-quickstart/Chain/chain`) + filter (`order-server`, `ChainService/chain`) — the
outbound CLIENT parents to the `order-server` filter span (innermost `makeCurrent`), the framework
SERVER span is left childless, and **every span exports (no ghost)**. Double-span + re-parenting is
real; ② (missing body + ghost) is not produced by a well-behaved filter with a correctly-wired
KrpcOtel.

## Step 2 — remaining hypotheses, tested

### 2a — KrpcOtel bound to a DIFFERENT OpenTelemetry than the CDI SDK: REPRODUCES ② (tested)
> **[KILLED — see CASE CLOSED ledger]**: 2a reproduces ② in the LAB but was **eliminated as the field
> cause in ROUND 4** (both SERVER spans, incl. `tech.krpc`, export in the field — see "ROUND 4") and
> superseded by the round-5 dependency drift + the mesh waypoint. Historical lab hypothesis, retained.
`OtelKrpcSeparateSdkQuarkusTest` + `KrpcSeparateSdkInstaller`: bind `KrpcOtel` to a real SDK with
**no span processor** (recording spans, valid injectable ids, never exported) while the filter keeps
the CDI SDK. Result — **field ② exactly**:
- filter SERVER spans (`order-server`) + jdbc export; **zero `tech.krpc` spans exported**;
- outbound CLIENT/hello body **missing** from export;
- downstream SERVER/hello parents to the krpc CLIENT's injected id, which has **no exported body =
  ghost**; **traceId continuity intact** (the krpc CLIENT inherits the filter span's trace via the
  shared JVM-global QuarkusContextStorage);
- each hop injects its own CLIENT id → a **distinct ghost per hop** — matches the field's three
  distinct ghost parent ids (c8045029/61788159/e8cd8c99).

This answers the task's 2a question directly: **with a real-but-no-exporter KrpcOtel instance, the
injected traceparent ids ARE real-but-unexported** (isNoop()==false, so interceptors do NOT
short-circuit — they create and inject valid spans that never reach a backend). Contrast the *noop*
sub-case: if `install()` got `OpenTelemetry.noop()` (or never ran, isResolvable()==false),
`isNoop()==true` → interceptors short-circuit → no `tech.krpc` spans AND no OTel injection; the wire
then carries only the MDC-forwarded inbound traceparent (`PropagateTraceCall`), so the ghost/parent
chain would be the MDC value (one shared id), not three distinct krpc CLIENT ids. The three distinct
ghosts therefore point specifically at **real-but-no-exporter**, not noop.

**Install audit (standard wiring does NOT have 2a).** `RpcServiceExpose` (`:105`) does
`KrpcOtel.install(openTelemetry.get())` from `@Inject Instance<OpenTelemetry>`. In the quickstart this
is the same bean the filter `@Inject`s → both export to the same sink: `OtelClientChainQuarkusTest`
(filter off) proves `tech.krpc` CLIENT+SERVER spans reach the CDI `RecordingSpanExporter`, and the
round-2 double-span dump shows `tech.krpc` and `order-server` spans side by side in one exporter. So
**KrpcOtel == CDI SDK in the standard path; 2a is a consumer-wiring deviation**, not a krpc bug.

### 2b — double-BSP (exporter under multiple bean types): explains ×2, NOT selective loss
Round-1 reproduced ×2 (exporter exposed under multiple CDI types → registered in the BSP twice). Can
it cause selective LOSS instead? Only via queue overflow (BSP default max 2048) or a shutdown race,
which drop spans **randomly**, not by instrumentation scope. The field loss is **consistent and
scope-selective** (krpc-scope always absent, consumer-scope always present) — that can only come from
the two span families travelling **different pipelines**, i.e. 2a. **UPDATE (CASE CLOSED): 2b is
KILLED even as the field #4 (×2) cause** — the consumer audit found zero programmatic
exporter/processor beans and the ×2 cleared with the dependency/filter fix alone (no exporter-wiring
change). The field ×2 was the double-SERVER-span filter + the mesh per-hop `traceparent` rewrite. 2b
survives only as a lab-reproduced failure mode + regression guard, and remains ruled out for the
selective CLIENT loss.

## Step 3 — discriminator matrix (gated on consumer ops' answer: one/two SERVER spans + scopes)
> **[KILLED — see CASE CLOSED ledger]**: this matrix was gated on a consumer answer that later
> resolved to the two-scope (double-SERVER filter) row + dependency drift; the 2a branch and the
> "#4 double-export hints" below were not the field cause. Historical decision aid, retained.

| Field trace shows… | Mechanism | ② present? | Primary fix |
|---|---|---|---|
| BOTH `tech.krpc` + `order-server` SERVER spans, all exported, CLIENT present | well-behaved filter double-span (round 2) | no ghost | delete the legacy filter (hygiene: removes duplicate + CLIENT re-parenting) |
| ONLY `order-server` SERVER spans; NO `tech.krpc`; CLIENT missing; 3 distinct ghost parents; continuity intact | **2a** — KrpcOtel on a non-CDI/no-exporter OpenTelemetry | **yes** | fix the KrpcOtel↔CDI-SDK binding on the edge servers; deleting the filter alone makes it WORSE (no SERVER span exports) |
| Spans duplicated ×2 | 2b — exporter bean under multiple types | orthogonal | expose the exporter/processor once |

**Edge-vs-internal reconciliation (important):** internal services (no filter) were "always clean" ⇒
their KrpcOtel exports (`tech.krpc` spans present + correct) ⇒ the *standard* wiring is fine. A GLOBAL
2a would have broken internal too (no SERVER span at all). So if the field is the 2a row, the
non-CDI/no-exporter KrpcOtel binding must be **edge-server-specific** — verify the 5 edge servers'
`OpenTelemetry` bean resolution / `Instance<OpenTelemetry>.get()` at `RpcServiceExpose` startup
differs from internal (the round-1 #4 double-export hints at non-standard/duplicated OTel bean wiring
on exactly those servers). The scope-name answer from ops resolves the row deterministically.

## Deliverables status
> **[KILLED — see CASE CLOSED ledger]**: the CONDITIONAL/PENDING rulings below were resolved — the
> filter deletion (cause #1) + dependency drift (cause #2) stand; the 2a "KrpcOtel bound to a
> different SDK" branch was never the field cause. Historical status, retained.
- (a) migration ruling: **CONDITIONAL / PENDING** the discriminator — delete the filter in the
  two-scope row (hygiene); in the 2a row the primary fix is the KrpcOtel binding and filter deletion
  is secondary (and harmful if done alone). Round-2 SPEC-CONSUMER line stands for the hygiene part.
- (b) krpc defensive change: still **NO speculative code** (round-2 argument holds). Optional, only if
  the 2a row is confirmed: krpc could log a one-time WARN at `install()` when the resolved
  `OpenTelemetry` has no registered span processor (a cheap, precise "you installed a no-exporter SDK"
  signal) — proposed, not shipped, pending confirmation.

## Round-3 tests (all green)
- Verbatim `LegacyOtelServerFilter`; `OtelKrpcSeparateSdkQuarkusTest` + `KrpcSeparateSdkInstaller`
  (2a reproduction of ②). Existing double-span/ghost/clean tests retained.
- No main-source change → NS-7 native rebuild not required; NS-3/NS-4 untouched. All quickstart tests
  + `:rpc-common/:rpc-client/:rpc-server(+noSdkTest)/:http-server` suites green.

---

# ROUND 4 — discriminator resolved (both SERVER spans exported); 2a & ext-rpc-un-ended both eliminated

## Ground truth (OpenObserve full-13-span recheck of e9af6ce0)
TWO Order/pay SERVER spans, siblings under the frontend parent, BOTH exported:
- `929d7760` name `order-server/Order/pay`, `rpc_system=grpc` + `rpc.grpc.status_code` → the built-in
  `OtelServerInterceptor` (KrpcOtel, `tech.krpc` scope) — **it exports**;
- `930b56f8` name `OrderService/pay`, `rpc_system=krpc` + business attrs → the legacy filter (jdbc
  children hang under it, filter innermost-current, matches round-2).
CLIENT `c8045029` (payment SERVER's parent) still has NO body. payment side: exactly ONE SERVER
(grpc), no filter, clean.

**⇒ Hypothesis 2a is DEAD.** `tech.krpc` spans DO export (the built-in SERVER 929d7760 is there), so
KrpcOtel is bound to the exporting CDI SDK; the export pipeline is healthy. Round-3's separate-SDK
row is ruled out by the field.

## ext-rpc client path = `RpcClientFactory.get` (bytecode), i.e. the round-3 harness path
`ext-rpc-1.0.3` `ClientRecorder`: `clientFactorySupplier` → `new RpcClientFactory(name,
ManagedChannelBuilder.forAddress(host,port)...build())` + `setDefaultCacheManager(...)`;
`rpcClientSupplier` → `RpcClientFactory.get(iface)`. The synthetic bean is `@ApplicationScoped`,
config prefix `quarkus.rpc.client.apps.<name>.url|scan`, enabled when `tech.krpc.client.ClientContext`
is present. **So `@Inject PaymentService` is exactly `RpcClientFactory.get` → `MethodCallProxyHandler`
→ `OtelClientInterceptor` + `PropagateTraceCall` → `blockingUnaryCall`** — the identical runtime call
path round-2/3 already exercised, plus a no-op `setDefaultCacheManager` (no CacheManager bean) and a
reused ApplicationScoped instance (round-3 harness caches the client too). The ext-rpc wrapper does
not touch the gRPC ClientCall lifecycle.

**@Inject assembly attempt (documented limitation, not a defect).** Stood up a remote-only
`RemoteService` + a second krpc server (:50052) + `quarkus.rpc.client.apps.remote.*` to consume via
the real synthetic bean. ext-rpc's build-time `ClientProcessor` scans the **application (main) index**
for `@RpcService` interfaces; a **test-scoped** interface is not indexed, so no synthetic bean is
generated (`UnsatisfiedResolutionException`) — an in-repo @QuarkusTest limitation, reverted. The
bytecode equivalence above + the round-3 double-span test (which asserts the `tech.krpc` CLIENT/hello
span **is exported** with the verbatim filter installed) cover the runtime behavior faithfully.

## CLIENT-span lifecycle audit (reachable paths)
`OtelClientInterceptor` ends the span on every reachable **successful-RPC** completion:
- sync `blockingUnaryCall`: `TracingClientCallListener.onClose` → `span.end()` (`:98`) — green in
  `OtelProductionChainTest`, round-3 `OtelDoubleServerSpanQuarkusTest` (CLIENT/hello exported under the
  verbatim double-span, otel 1.57, real KrpcOtel);
- non-OK status / exception: `onClose(status)` → `setStatus(ERROR)` + `end()` — `OtelProductionChainTest`;
- async (`asyncUnaryCall`): `onClose` → `end()` — `GhostClientSpanTest`;
- deadline: gRPC delivers exactly one `onClose(DEADLINE_EXCEEDED)` for a started unary call → `end()`.

The ONLY un-ended window: `TracingClientCall.start` throws AFTER `startSpan()` but BEFORE
`super.start(new TracingClientCallListener(...))` installs the listener (i.e. `inject()`/`makeCurrent()`
throws, `OtelClientInterceptor:57-70`). That leaks the span — **but it also fails the RPC** (the call
never starts), so the downstream would not receive `c8045029` and both SERVER spans would not both
appear. The field RPC **succeeded** (payment processed, both SERVERs exported) ⇒ this is NOT the field
trigger.

## Conclusion: ② is NOT reproducible on the reachable krpc/ext-rpc code with a healthy SDK
On a **successful** RPC (which the field is) the CLIENT span is created, injected, AND ended → it
reaches the same BSP that exported the sibling SERVER span (same KrpcOtel/CDI SDK). The field's
"c8045029 injected but no body, everything else exported" contradicts the reachable code. 2a
(different SDK), the well-behaved filter, and the ext-rpc wrapper are all eliminated. The missing
CLIENT therefore requires a factor OUTSIDE the reachable krpc/ext-rpc code.

## Decisive next artifact (requested from consumer ops)
> **[KILLED — see CASE CLOSED ledger]**: this round-3/4 artifact request (and its double-BSP / custom
> `ClientInterceptor` / native-listener hypotheses below) is superseded — the real cause was the mesh
> waypoint (Envoy), confirmed by the infra exhibits. Historical investigative ask, retained.
On the **Order** server, for the reproduced trace, a `BatchSpanProcessor`/SDK self-diagnostic answering
**is `onEnd` called for span `c8045029`?**
- `onEnd` NOT called → the span truly never ended → look for a **non-krpc gRPC `ClientInterceptor`** on
  the consumer's channel (below/around krpc's) that breaks the listener chain, or a native-image
  listener-wrapping issue (is the edge server running native?). krpc's own listener demonstrably ends.
- `onEnd` called but not exported → selective export drop. The #4 double-BSP (exporter under multiple
  bean types) drops on queue-overflow/shutdown **randomly**, not by scope, so it cannot by itself
  explain a consistent CLIENT-only loss; look for a span **processor/exporter filter** or a sampler
  that treats CLIENT differently.

Also confirm: does the edge server register any custom `ClientInterceptor`/gRPC channel config, and is
it JVM or native?

## Discriminator matrix — RESOLVED
Field = the **two-scope** row: both `tech.krpc` + `order-server` SERVER spans exported. Per the matrix
that row is "well-behaved filter double-span, no ghost from the filter." The field additionally shows a
missing CLIENT, which that row does NOT attribute to the filter — so ② is a SEPARATE, still-open
defect requiring the onEnd artifact above.
> **[KILLED — see CASE CLOSED ledger]**: "② is a SEPARATE, still-open defect" was the round-4 status.
> ② is RESOLVED — the missing-CLIENT/ghost was the dependency drift (`rpc-client:1.0.3`, no CLIENT
> interceptor) + the mesh waypoint (Envoy) rewriting `traceparent`; the `onEnd` artifact was never
> needed. The double-span (two-scope) ruling stands.

## Deliverables status (round 4)
- (a) DELETE the legacy filter: **CONFIRMED** hygiene (double-span pollution proven by the field's two
  SERVER spans) — but explicitly NOT the ② fix. SPEC-CONSUMER line from round 2 stands.
- (b) krpc defensive change: **proposed, not shipped** (guardrail: no field-trigger repro). If ops
  confirm `onEnd` is never called, the defensive hardening becomes justified — make
  `TracingClientCall.start` end the span if it throws after `startSpan()` (try/catch around
  inject+super.start → `span.end()` on throw), closing the only latent un-ended window. Sketch, not
  shipped: it would be a main-source change (NS-7 native rebuild) and does not match the current
  (successful-RPC) field trigger.

## Round-4 tests
- No new committed tests (the ext-rpc @Inject assembly was reverted due to the main-index scan
  limitation). Round-3's `OtelDoubleServerSpanQuarkusTest` already asserts the `tech.krpc` CLIENT span
  exports on the ext-rpc-equivalent path with the verbatim filter — the "② not reproduced" evidence.
- No main-source change → NS-7 native rebuild not required; NS-3/NS-4 untouched. All quickstart +
  `:rpc-common/:rpc-client/:rpc-server(+noSdkTest)/:http-server` suites green.

---

# ROUND 5 — SOLVED: deployment/dependency drift → the edge servers run a pre-OTEL-001 rpc-client

## Root cause (structural, proven from POMs + bytecode — not HEAD)
`rpc-server-quarkus` depends on `rpc-server` (api) + `ext-rpc` (impl); it does **NOT** depend on
`rpc-client`. `ext-rpc:1.0.3`'s POM pins `rpc-client:1.0.3` (verified: `<artifactId>rpc-client</artifactId>
<version>1.0.3</version>`). So for a consumer whose only `rpc-client` source is the transitive one from
`rpc-server-quarkus:1.1.1 → ext-rpc:1.0.3`, **rpc-client resolves to 1.0.3** — a **pre-OTEL-001**
client with **no `OtelClientInterceptor`** — while `rpc-server`/`rpc-common` are 1.1.1. Unless the app
explicitly declares `rpc-client:1.1.1`, the deployed image carries the OLD client.

This is exactly field ②, and it reconciles every prior round:
- **CLIENT bodies missing** — rpc-client < 1.1.1 has no `OtelClientInterceptor`, so it creates NO CLIENT
  span at all (not "un-ended" — *never created*). Round 4's audit (1.1.1 client always ends+exports)
  was right; the field simply isn't running 1.1.1's client.
- **Both SERVER spans exported** — rpc-server 1.1.1's built-in `OtelServerInterceptor` (`tech.krpc`,
  grpc) + the legacy filter (`order-server`, krpc), KrpcOtel real. Matches the discriminator.
- **traceId continuity intact** — the old client forwards `MDC.get("traceparent")` **verbatim** (W3C).
- **Ghost parent, distinct per edge server** — the old client does NOT create a span for the id it
  forwards; that id is the **upstream/gateway-injected** traceparent. If the frontend/gateway isn't in
  the krpc OTel backend (different system), its id has no span object → ghost; the frontend injects a
  **distinct** id per edge call → a distinct ghost per edge server (c8045029/61788159/e8cd8c99).
- **Works with 1.1.1 servers** — W3C `traceparent` is read by 1.1.1's extractor.
- **Internal services (payment/ledger) clean** — no legacy filter, and (leaf or 1.1.1-client) no
  missing-CLIENT symptom.

## Correction to the round-5 premise
No rpc-client version **mints** a fresh span-id in the client — every version forwards
`MDC.get(...)` **verbatim**. The "distinct ghost per hop" is NOT client minting; it is distinct
**upstream-injected** ids forwarded unchanged by the old client (which, lacking `OtelClientInterceptor`,
neither creates nor overwrites). The B3-era versions read/forward `x-b3-*` from MDC; still no mint.

## Version-fingerprint table (outbound trace path per rpc-client version)

| rpc-client | wire header | `OtelClientInterceptor` / CLIENT span object | outbound value | mints id? |
|---|---|---|---|---|
| 1.0.0.rc1 | `x-b3-*` (B3 multi) | NO | forwards MDC `x-b3-spanid`/`-parentspanid`/`-sampled`/`-flags` verbatim | no |
| 1.0.1 | `traceparent` (W3C) | NO | forwards MDC `traceparent` verbatim | no |
| 1.0.2 | `x-b3-*` (B3 multi) | NO | forwards MDC `x-b3-*` verbatim | no |
| **1.0.3** (ext-rpc 1.0.3 pins this) | `traceparent` (W3C) | **NO** | forwards MDC `traceparent` verbatim | no |
| 1.1.0 | `traceparent` (W3C) | NO | forwards MDC `traceparent` verbatim | no |
| **1.1.1** (HEAD) | `traceparent` (W3C) | **YES** — creates CLIENT span, injects a fresh id (overwrites MDC), ends in `onClose`, exports | injects a real, exported CLIENT span id | creates+exports a span |

Server side for the same eras: `ServerContext` forwards the inbound `traceparent` into MDC **verbatim**
(no mint) in 1.0.3/1.1.0/1.1.1; the `OtelServerInterceptor` (SERVER span) exists only from 1.1.1.
Consequence: continuity holds across a mixed fleet, but a hop whose client is < 1.1.1 contributes no
CLIENT span and forwards whatever id it received.

B3 versions (1.0.0.rc1, 1.0.2) emit only `x-b3-*`, which a 1.1.1 server does NOT read → they would
**break** continuity with a 1.1.1 downstream. The field has continuity ⇒ the deployed client is a
**W3C** version (1.0.1 or **1.0.3**), consistent with the ext-rpc:1.0.3 pin.

## Instant image-match check (for the consumer)
On the deployed **order/catalog/user/technician/merchant** image:
1. `mvn dependency:tree | grep 'tech.krpc:rpc-client'` (or `jar tf` the app / inspect the fat-jar) —
   **if it shows `rpc-client:1.0.x` or `1.1.0`, that is the root.**
2. Instant fingerprint without versions: is `tech/krpc/client/OtelClientInterceptor.class` present in the
   deployed `rpc-client`? **Absent ⇒ pre-OTEL-001 client ⇒ no CLIENT spans by design.**
3. Cross-check: the internal (clean) services will show the SAME rpc-client version — so the
   differentiator for the ghost is the legacy filter (double SERVER span) layered on top, not the
   client version. Both coexist in the edge images.

## Fix direction (NOT this round — cross-repo + consumer build)
The `rpc-client` version must be forced to 1.1.1 to restore CLIENT spans. Options (for the SPEC/train,
out of this worktree's scope): (a) bump `ext-rpc` to depend on `rpc-client:1.1.1`; (b) have
`rpc-server-quarkus` also declare `rpc-client` at the krpc version so it wins resolution; (c) consumer
adds an explicit `rpc-client:1.1.1` dependency / BOM constraint. The legacy-filter deletion (round 2/4)
remains an independent hygiene fix for the double SERVER span.

## Round-5 deliverable
Pure archaeology; no code/tests changed. Evidence: `ext-rpc-1.0.3.pom` (pins rpc-client 1.0.3),
`rpc-server-quarkus` deps (no rpc-client), and decompiled `MethodCallProxyHandler`/`PropagateTraceCall`/
`ServerContext` for 1.0.0.rc1/1.0.1/1.0.2/1.0.3/1.1.0/1.1.1 (fingerprint table above). Findings only.

---

# ROUND 6 — "wire-injected parent-id ≠ exported CLIENT span-id": a SECOND CLIENT injector

> **KILLED (CASE CLOSED).** The "second CLIENT injector / OTel gRPC auto-instrumentation" hypothesis
> was wrong: no such injector existed in the consumer (all-native, 0 `-javaagent`; no `-grpc` OTel lib
> on the classpath). The fresh bodiless per-hop id was the **mesh waypoint (Envoy)** rewriting
> `traceparent`, confirmed by the infra seat and cleared by removing the `Telemetry` CR — 3/3 green
> (see CASE CLOSED, top). The lab `OtelClientDoubleInjectionTest` remains valid as a characterization
> of what a deeper injector *would* do, and as a regression guard; it is not the field cause.
Consumer fixed the drift (forced rpc-client:1.1.1 + deleted filters): item ① callback chain FIXED,
CLIENT bodies now exported/attached, double-SERVER and ×2 gone. Remaining, three hops same shape:
the wire traceparent's span-id ≠ the exported CLIENT span-id (a fresh, bodiless id), traceId
continuity intact.

## What the reachable krpc/ext-rpc code does (proven: wire == CLIENT)
- `makeCall` (`MethodCallProxyHandler:136-143`) builds `PropagateTraceCall`(OUTER) around the
  OTel-intercepted call (INNER). On `start()`, PropagateTraceCall puts the MDC value FIRST, then the
  inner `OtelClientInterceptor.TracingClientCall.start` injects via `KrpcOtel.METADATA_SETTER`
  (removeAll+put) LAST → the CLIENT-span id is the single wire value. **OTel wins; wire == CLIENT.**
- Enforced green by `OtelClientMdcParityTest.sdkPresent_clientSpanTraceparent_supersedesStaleMdc_singleHeader`
  (asserts `outSpanId == exported CLIENT.spanId`) and the quickstart chain test
  (`SERVER/hello.parent == exported CLIENT/hello.spanId`).
- **ext-rpc 1.0.4 adds no injector.** `ClientRecorder` (1.0.4) = `new RpcClientFactory(name,
  ManagedChannelBuilder…build())` + `setCacheManager(...)` + `RpcClientFactory.get(iface)` — a plain
  channel; the OTel interceptor is added by 1.1.1's `MethodCallProxyHandler`, same as the harness.
  The 1.0.4↔1.1.1 binary mix is API-compatible (`get`, `setCacheManager` present in 1.1.1); no extra
  `ClientInterceptor` class in ext-rpc 1.0.4 or its deployment. Quarkus OTel MDC writes
  `traceId/spanId/sampled/parentId` — NOT `traceparent` — so it does not feed `makeCall`'s
  `MDC.get("traceparent")`.

**Conclusion: the reachable krpc 1.1.1 + ext-rpc 1.0.4 code satisfies wire == CLIENT. The field's
wire ≠ CLIENT requires a SECOND, DEEPER CLIENT injector on the channel** — one that runs AFTER krpc's
`super.start(...)` and overwrites the traceparent with its own (fresh, non-recording → unexported)
span id. This is the CLIENT analog of the round-2 double-SERVER-span legacy filter.

## Mechanism reproduced (red-first, deterministic)
`OtelClientDoubleInjectionTest` adds a deeper `ClientInterceptor`-equivalent that overwrites the
traceparent with a fresh id after krpc's OTel inject. Result — the field signature exactly: exported
CLIENT span id ≠ wire span-id, wire span-id has NO exported span (ghost), traceId continuous. It also
documents that **no krpc-only injection order can win**: the deeper interceptor runs after krpc's
`super.start`, so whatever it writes is the last word before the transport.

> **[KILLED — see CASE CLOSED ledger]**: the "OTel Java agent / gRPC auto-instrumentation second
> injector" was hypothesized here and later disproven — the consumer runs all-native (0 `-javaagent`)
> and the per-hop id was the mesh waypoint (Envoy). Read this subsection as the historical hypothesis.

## The second injector — prime suspect: OTel gRPC auto-instrumentation (javaagent)
The consumer's declared stack (`rpc-server-quarkus` + `ext-rpc` + `quarkus-opentelemetry`) pulls
`opentelemetry-instrumentation-api` but **NOT** `opentelemetry-instrumentation-grpc` and no
`quarkus-grpc` — so no gRPC client interceptor is added to krpc's plain `ManagedChannelBuilder`
channels from the classpath. That leaves the **OpenTelemetry Java agent** (`-javaagent:opentelemetry-
javaagent.jar`, which bytecode-instruments `io.grpc.*` DEEPER than any app-level interceptor) as the
prime source of the second CLIENT span + injection. When the agent's gRPC instrumenter does not
suppress-and-propagate krpc's already-current CLIENT span (version/config dependent) it mints its own
span and injects it; if that span is non-recording/suppressed it never exports → the wire id is a
ghost. This is exactly what the reproduction models.

## Round-3 reconciliation
Round 3's "OTel injection always wins / nothing mints" holds for KRPC in isolation (re-proven above).
It is "contradicted by this assembly" only because the assembly contains a second injector (the agent)
that krpc neither controls nor can out-order — not because krpc's own path changed.

## Decisive artifact (to confirm before any fix — 先复现后修)
1. **Exported CLIENT span's instrumentation scope** for a field hop (2733a530 prepay / bd2fe3f4
   callback): `tech.krpc` ⇒ krpc created the exported CLIENT span and a SECOND (agent) injector put the
   ghost on the wire; `io.opentelemetry.*grpc*` ⇒ the agent created the exported one and krpc's is the
   ghost. Either way = double CLIENT instrumentation.
2. **Is the OTel Java agent on the edge JVMs?** (`-javaagent`, `OTEL_JAVAAGENT_ENABLED`,
   `otel.instrumentation.grpc.enabled`). And any consumer-added `GrpcTelemetry`/`ClientInterceptor` on
   the krpc channel.

## Fix direction (no speculative krpc code shipped this round)
No krpc-only code change can guarantee wire == CLIENT against a DEEPER injector (proven by the
reproduction: the deeper interceptor writes last). The fix is **single CLIENT instrumentation**:
- **Recommended:** disable the agent's gRPC client instrumentation
  (`-Dotel.instrumentation.grpc.enabled=false`, or exclude grpc from the agent) — krpc already
  instruments the CLIENT correctly (wire == CLIENT, app-level, scope `tech.krpc`). This is the CLIENT
  analog of deleting the legacy SERVER filter.
- Alternative: disable krpc's client interceptor (`KRPC_OTEL=false`) and let the agent be the sole
  authority — loses krpc's rpc.* attributes; not recommended.
- A krpc 1.1.2 affordance (only if the consumer wants coexistence): detect an existing recording
  CLIENT span in `Context.current()` at `OtelClientInterceptor.interceptCall` and defer (do not create
  a second span) — but this only helps when krpc is the OUTER/first injector; against a deeper agent
  injector it cannot fix the wire value. Proposed, NOT shipped (guardrail; the second injector is
  unconfirmed pending the artifact above).

## Round-6 deliverable
`OtelClientDoubleInjectionTest` (new, green) reproduces the field signature via a second injector and
proves krpc alone is authoritative. No main-source change → NS-7 native rebuild not required;
NS-3/NS-4 untouched. Suites green: `:rpc-client:test` (incl. the new + parity tests),
`:examples:quickstart:test`. The literal ext-rpc-1.0.4 synthetic-bean assembly is not stood up in-repo
(build-time main-index scan limitation, round 4) — the runtime path is bytecode-identical to
`RpcClientFactory.get`, covered by the green chain test.

---

# ROUND 7 — native gRPC path wire == CLIENT; mesh (Envoy) root cause confirmed (CASE CLOSED)

## Javaagent eliminated
All 8 consumer services are GraalVM **native** binaries — no `-javaagent` mechanism exists in native
(repo-wide grep 0 hits). So round-6's prime suspect (OTel Java agent gRPC instrumentation) is DEAD as
stated. The remaining in-process suspect was native init/context divergence; the remaining
out-of-process suspect is a wire-level injector.

## Native-mode wire == CLIENT parity — CONFIRMED (empirical)
Built the quickstart **native image** from this branch (`quarkusBuild -Dquarkus.native.enabled=true
-Dquarkus.package.jar.enabled=false`; a real Mach-O arm64 binary) with a TEMPORARY probe (reverted
after; not in the committed example): quarkus-opentelemetry + a console `SpanExporter` (dumps
spanId/parentSpanId) + a `@Startup` bean that makes one outbound krpc call to the in-image Hello
service, so the native `OtelClientInterceptor` creates a real CLIENT span at native runtime.

Native binary stdout (trace 547e68f7…):
```
[NATIVE-PROBE] hello ok=true
[NATIVE-SPAN] kind=SERVER name=quickstart/Hello/hello span=0f0cd0adca9a9fa9 parent=3aca7a8862deb062 scope=tech.krpc
[NATIVE-SPAN] kind=CLIENT name=quickstart/Hello/hello span=3aca7a8862deb062 parent=0000000000000000 scope=tech.krpc
```
**SERVER/hello.parent (`3aca7a8862deb062`, the wire traceparent id) == CLIENT/hello.spanId
(`3aca7a8862deb062`).** wire == CLIENT under GraalVM native runtime, same trace, both scope
`tech.krpc`. (Native detail: the manual `RpcClientFactory.get(HelloService)` needed a
`reachability-metadata.json` proxy registration — the ext-rpc build step does this automatically via
`NativeImageProxyDefinitionBuildItem`; not a tracing issue.)

**What the probe establishes (only this):** on the **native in-image gRPC CLIENT→SERVER** path, the
wire `traceparent` id equals the exported krpc CLIENT span id — i.e. no native-runtime gRPC-client
injection divergence. The probe did **not** exercise the HTTP face, and the literal ext-rpc synthetic
bean was not stood up in-repo (build-time main-index scan limitation, round 4); those paths are
covered by the JVM parity/chain tests, not by this native probe. So the correct joint statement is:
the **native probe** rules out native gRPC-client divergence, and the **field 3/3 closure** (CASE
CLOSED, top) proves the wire pollution was not in-process at all — together eliminating the in-process
class. Do not read the probe alone as eliminating every in-process candidate on both faces.

## Root cause CONFIRMED by the infra seat: the waypoint (Envoy) mesh tracing
> This was recorded as the "prime suspect" at the time of round 7; it was subsequently **CONFIRMED by
> the infra seat's own config archaeology + a dismantle-then-green**, not by any in-repo capture.
> Source: exhibit `EVIDENCE-20260719-0049-iac-otel003-mesh-confirmed.md` (confirmation + dismantle)
> and `EVIDENCE-20260719-0112-iac-otel-fullcase-closed.md` (3/3 green after).

What the infra seat found and did (their account, verbatim exhibit):
- Consumer infra is **Istio ambient mesh**; east-west service-addressed traffic is steered by ztunnel
  to the destination namespace's **waypoint (L7 Envoy)** — all three field hops traverse it.
- Mesh tracing **was on, and the infra seat had turned it on**: on 2026-07-16 they applied a
  `Telemetry` CR in two stage namespaces (100% sampling, provider = OpenObserve OTLP gRPC) as an
  **ingest comparison**. Envoy's OTLP exporter carried **no auth header → OpenObserve 401-rejected
  every Envoy span**, while Envoy still **minted a fresh span-id per hop and rewrote `traceparent`**.
  "spans never reach the store + header rewritten" = one ghost parent per hop, matching the three-hop
  table exactly. This **also explains the rc1-era ghosts** (when krpc had no CLIENT interceptor, the
  ghost id was Envoy's; the infra seat retracted their earlier "krpc CLIENT id not exported" attribution).
- **The actual infra change performed:** the `Telemetry` CR was **deleted** (gitops commit `136ab04`,
  Argo prune confirmed live); the `meshConfig` provider was left in place but lazy (no CR reference).
- **Before → after:** before, Envoy rewrote `traceparent` per hop and its spans 401'd out of the
  store (ghost parents); after the delete, the 3/3 field re-run is green with app CLIENT→SERVER
  parentage intact and zero ghosts (see CASE CLOSED).

### Notes for the durable file (config-safe)
- The fix that worked was **removing the waypoint `Telemetry` CR** (stop the mesh emitting/rewriting
  trace context for these hops). This is not the same as Istio's `disableContextPropagation`, which
  **strips** trace-context headers (it does not preserve the app's original header), nor
  `disableSpanReporting`, which keeps propagation active — neither is the change that was made; treat
  them as distinct knobs, not interchangeable.
- The span attributes used to fingerprint an Envoy span (e.g. `component=proxy`, a waypoint peer
  address) are **observed/exporter- and semantic-mapping-dependent values, examples not a portable
  contract**; discriminate by matching the wire span id to a span that **no app component
  (`tech.krpc`) created**, then to the mesh's own telemetry.
- Reopen plan (infra, separate project): re-enable the waypoint tracer **with an auth'd Envoy
  exporter** so Envoy spans land in the same store and the parent chain closes through the mesh.

## Round-7 deliverable
Findings only (native parity was a one-off verification; the temporary probe/deps/config and the
`reachability-metadata.json` were reverted — the published example is unchanged). No committed
code/test change this round. The native probe ruled out native gRPC-client injection divergence on the
tested path; the mesh root cause was then confirmed by the infra seat (exhibits) and the field went
3/3 green after the waypoint `Telemetry` CR was removed (see CASE CLOSED).
