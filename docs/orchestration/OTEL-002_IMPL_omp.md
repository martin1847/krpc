# OTEL-002 — Implementation & Findings (omp)

Branch `fix/otel-002` off `origin/dev @ 5e5b120` (the OTEL-001 merge, #23). Reviewer: codex.
Scope: three field-found OTel/logging defects from staging rc1 + red-first tests + this doc.
No wire change (NS-4); OTel = API-only, no SDK in core (NS-3); touched main sources native-clean (NS-7).

Baseline confirmed green before changes: `OtelProductionChainTest`, `HttpOtelSpanTest`.
Round 2 folds in the codex r1 review (R1-1..R1-8); see the per-finding section at the end.

---

## Fix 1 — HTTP inbound context does not reach outbound krpc client calls

### Root cause (verified, not the premise's guess)

The premise guessed the HTTP SERVER span "is not made current around the handler body". **False** —
OTEL-001 already binds the OTel SERVER span across `handle(...)` via `span.makeCurrent()`, proven by
`HttpOtelSpanTest` and by my SDK-mode `HttpToKrpcClientChainTest` (green on dev).

The real defect: the **ADR-0003 MDC trace binding the HTTP face never performed**. The gRPC face
binds inbound W3C context into MDC in `ServerContext` (`ServerContext.java:96-107`) and clears it in
`UnaryMethod`; the HTTP face bound only the OTel span. Consequences:

1. **No-SDK deployments lose the trace on outbound calls.** With no SDK, continuity relies solely on
   ADR-0003 MDC forwarding: `MethodCallProxyHandler.makeCall` (`:138-142`) reads
   `MDC.get(MDC_TRACEPARENT)` and wraps the call in `PropagateTraceCall`. Empty MDC → no
   `PropagateTraceCall` → downstream gets no `traceparent` → new trace.
2. **Handler logs carried no `traceId`/`spanId`.**

### Fix
`AbstractHttpHandler.bindTraceMdc(...)` mirrors `ServerContext`'s inbound binding, called before the
handler body; `MDC.clear()` in `finally`. Independent of OTel span creation. Hardened per r1:

- **R1-4 (strict validation):** only a fully valid W3C `traceparent` is bound/forwarded.
  `TraceMeta.parse` now strictly validates lower-case hex, field lengths, version ≠ `ff`, and
  non-zero trace/span ids; malformed or absent → **no** trace MDC keys set → zero outbound
  `traceparent` (never an incoherent one). Valid → exactly one.
- **R1-5 (MDC identifies the active span):** once the HTTP SERVER span is current (SDK mode), logging
  MDC `traceId`/`spanId` are overwritten from **its** `SpanContext`, so a handler error log joins the
  span that recorded the error. The inbound `traceparent` is retained in MDC only for the no-SDK
  legacy forward.
- **R1-8 (async boundary):** documented on `bindTraceMdc` — the MDC/OTel scope covers the
  **synchronous** handler body only; work a handler schedules onto its own executor/`CompletableFuture`
  after `handle()` returns must capture/propagate context itself (out of the framework's scope).

### Tests
- `HttpTraceMdcPropagationTest` (no SDK, logback): valid → forwarded once; **absent → 0**;
  **malformed → 0** (R1-4); **duplicate header → exactly 1**. `validInbound...` is the red-first
  case (RED `expected:1 but was:0` on dev).
- `HttpToKrpcClientChainTest` (SDK): full SERVER→CLIENT→SERVER chain + exactly-one-traceparent;
  `capturedHandlerMdcIdentifiesTheHttpServerSpan` asserts handler MDC == exported SERVER span id, not
  the inbound parent (R1-5).

---

## Fix 2 — "ghost CLIENT spans": OPEN at the independent A/B export boundary; in-process lifecycle excluded

### Field report
Downstream SERVER spans reference CLIENT span-ids as parent, but no CLIENT span body reaches the
backend; traceId continuity intact.

### What the in-process test establishes (and what it does NOT — R1-6)
`GhostClientSpanTest` rebuilds `BatchSpanProcessor` (async flush) + `parentBased(alwaysOn)` +
`forceFlush`, versus OTEL-001's `SimpleSpanProcessor`, and shows the CLIENT span body **is exported
with correct parentage** on both the sync (`blockingUnaryCall`) and async (`asyncUnaryCall`,
`@Deprecated AsyncClient`) paths (exception path already covered by `OtelProductionChainTest`).

**This excludes an in-process krpc span-lifecycle bug ONLY under shared-pipeline assumptions.** It
does not reproduce the field's independent A/B boundary: A and B share one `OpenTelemetrySdk`/
exporter here and the flush always succeeds. It therefore cannot exercise:
- A exporting its CLIENT span through a **separate** OTLP pipeline/collector while B exports its
  SERVER span through its own;
- an **A-side batch queue drop** (queue full / exporter error);
- **A pod termination before its BatchSpanProcessor drains** (no successful flush).

Any of these produces exactly the field signature (B's SERVER references A's injected CLIENT id;
A's CLIENT body never arrives), and none is reachable from a single-process shared-exporter test.

### Sampler-flag argument — qualified (R1-6)
"A sampler dropping the CLIENT span would also unsample the downstream, so the field's intact
downstream implies the CLIENT was sampled" holds **only if service B uses parent-based head
sampling**: a non-recording CLIENT context injects flag `00` and the default
remote-parent-not-sampled branch drops B's span. Under independent or tail sampling at B this
argument does not hold, so it cannot by itself exclude the report.

### Conclusion / cutover gate
No speculative fix shipped (先量再改). The ghost is **OPEN** at the independent export/flush boundary
and is gated on the consumer's staging window (they offered live repro access + A-side
exporter/collector delivery evidence for the reported trace). Cutover gate includes "CLIENT span
body appears in the backend for the reproduced trace". A two-process/container rig with separate A/B
SDK+exporter pipelines and staging collector config (including termination without a successful
flush) is the correct reproduction; it is **not** shipped in this repo (deliberately — it would be a
staging/infra artifact, not a core unit test). `GhostClientSpanTest` is retained as a regression
guard, relabelled to state precisely what it proves.

---

## Fix 3 — sensitive-header redaction (JWT plaintext in DEBUG logs)

### Policy: MASK BY DEFAULT (R1-2)
`tech.krpc.util.LogRedact` (rpc-common — shared by both faces). A deny-list cannot make a full
arbitrary-header dump safe (the next unlisted `X-Token`/`X-Goog-Api-Key`/custom auth header leaks),
so every header value is **masked** (`<redacted>`, a fixed sentinel — never a prefix of the secret)
UNLESS its name is on an explicit `DIAGNOSTIC_SAFE` allow-list (content negotiation/framing,
`user-agent`/`host`/`connection`/`date`/`cache-control`, `traceparent`/`tracestate`/`x-request-id`,
`mcp-protocol-version`/`c-id`/`x-krpc-http-status`). `cookie`/`set-cookie` keep cookie **names** and
mask every value; a bare (`=`-less) non-empty segment is masked whole (R1-3 — it may itself be the
secret).

### Application
`AbstractHttpHandler` logs inbound requests at DEBUG through `redactHeaders` (lower-cased keys,
masked values). **R1-1:** only the raw **path** (`QueryStringDecoder(uri).rawPath()`) is logged, never
the query string (which can carry `?access_token=...`).

### Audit table

| Site | Emits | Action |
|---|---|---|
| `AbstractHttpHandler` inbound log (`:126`) | raw path + headers/cookies | path-only + `LogRedact` (mask-by-default) |
| `AbstractHttpHandler:~421` `Parse Post Json` | request **body** (DEBUG) | residual — see below |
| `ServerContext`/`UnaryMethod` (gRPC) | method setup, MDC clear | no full metadata dump — none needed |
| `JwsVerify`/auth path | url, JWKS kids, error messages | no token value logged — none needed |
| `InitJwsVerify` | cookie **name** (config) | not a value — none needed |
| marshallers, `AsyncMethod:106` | sizes, code/message | no credential — none needed |

### Residual (documented, out of header scope)
`AbstractHttpHandler` `Parse Post Json` logs the raw POST body at DEBUG. Bodies are app DTO content
the framework cannot classify per-field; DEBUG is off in prod by default (observability env matrix).
A follow-up could add DTO field-level masking. Out of scope for this hotfix.

### Tests
- `LogRedactTest` (8): default-deny for `Authorization` + unlisted token-shaped names
  (`X-Token`/`X-Goog-Api-Key`/...), allow-list pass-through, cookie name-keep/value-mask, **bare
  segment masked** (R1-3), case-insensitivity, null-safety.
- `HttpHeaderRedactionTest` (4): captures the **fully rendered encoder output** (`%mdc` + `%msg`,
  R1-7); masks Authorization + `access-token` cookie; **query credential not logged** (R1-1);
  **unlisted `X-Token`/`X-Goog-Api-Key` masked** (R1-2); no live token in any rendered line.

---

## Verification

- Touched-module suites (`--rerun-tasks`, `--max-workers=2`): `:rpc-common:test`, `:http-server:test`,
  `:rpc-server:test`, `:rpc-server:noSdkTest`, `:rpc-client:test`, `:examples:quickstart:test`
  (incl. `OtelServerSpanQuarkusTest`) — all green.
- **NS-7 native**: main sources changed (`AbstractHttpHandler`, `LogRedact`, `TraceMeta`). Quickstart
  native image rebuilt (`quarkusBuild -Dquarkus.native.enabled=true --link-at-build-time
  --no-fallback` — an unresolved-reflection surface would fail the link) and booted (HTTP :8080
  `/agent/*` on `AbstractHttpHandler`, gRPC :50051), `/agent/*` return 200 with
  traceparent/cookie/Authorization, exercising `bindTraceMdc`. New code adds no reflection/resource/
  proxy surface (`LogRedact`/`TraceMeta` = `String`/`Set`/`Locale`; `MDC` already native-present).

## ADR-0006
No amendment: pure bug fixes + a logging-hygiene helper. ADR-0003's MDC design and ADR-0006's
span/coexistence design are unchanged; Fix 1 fills a gap (HTTP face never did the MDC binding the
gRPC face always did).

## Round 2 — codex r1 responses (R1-1..R1-8)

| Finding | Resolution |
|---|---|
| R1-1 query in log | Log `rawPath()` only; `HttpHeaderRedactionTest.queryStringCredentialIsNotLogged`. |
| R1-2 deny-list bypass | `LogRedact` flipped to allow-list mask-by-default; negative tests for `X-Token`/`X-Goog-Api-Key`. |
| R1-3 bare cookie leak | `maskCookie` masks bare non-empty segments; leak-locking test replaced by `bareCookieSegmentIsMasked`. |
| R1-4 malformed W3C forwarded | `TraceMeta.parse` strict; HTTP face binds/forwards only when valid; absent/malformed/duplicate tests. |
| R1-5 MDC = inbound span | MDC `traceId`/`spanId` overwritten from the active SERVER span's `SpanContext`; captured-MDC test. |
| R1-6 ghost exclusion overclaimed | Conclusion rewritten: in-process excludes lifecycle **under shared-pipeline only**; A/B export/flush boundary OPEN, gated on staging. Sampler argument qualified to parent-based head sampling. Test relabelled. |
| R1-7 encoder capture | Test now asserts the full rendered encoder line (`%mdc`+`%msg`) + adversarial variants. |
| R1-8 async continuation | Documented on `bindTraceMdc` + here; no new API. |

## Round 3 — codex r2 closure responses (R2-1, R2-2)

| Finding | Resolution |
|---|---|
| R2-1 parse breaks W3C future versions | `TraceMeta.parse` is now forward-compatible: version `00` = exactly four fields; a higher (non-`ff`) version validates the four common fields and accepts opaque trailing version-specific fields, so the header is forwarded verbatim. Tests: `HttpTraceMdcPropagationTest` version-01 four-field forwarded, version-01+extension forwarded verbatim, version-`ff` rejected, version-`00`+trailing rejected. |
| R2-2 gRPC face forwards invalid W3C | `ServerContext` now parses BEFORE writing any trace MDC (`traceparent`/`traceId`/`spanId`/`tracestate`/`x-request-id`); invalid → none set → zero outbound. Test: `ServerTraceMdcPropagationTest` (noSdkTest) — malformed gRPC inbound → 0 forwarded; valid + future-version+ext → forwarded verbatim. |
| advisory (LogRedact "never PII") | Comment softened to "no framework-recognised credentials; values may still carry caller-controlled text (user-agent / x-request-id / tracestate)". |

## Scope honesty
- Fix 2 ships **no code change** (OPEN at the export/flush boundary; gated on staging repro).
- Test-scope build edits only (`http-server/build.gradle`: `rpc-client`/`rpc-server`/`grpc-netty` +
  logback, test scope). No runtime/publish dependency added (NS-3 intact).
- Main-source change on the gRPC face: `ServerContext` now validates the inbound traceparent before
  binding trace MDC (R2-2), so the gRPC face no longer forwards a malformed inbound context — the
  same guarantee as the HTTP face. `TraceMeta.parse` (shared, forward-compatible per R2-1) backs both.
