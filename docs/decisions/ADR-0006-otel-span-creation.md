# ADR-0006: OpenTelemetry Span Creation In The Framework

Status: accepted

Date: 2026-07-16

Amends: ADR-0003 (supersedes its "the framework creates no spans" clause; ADR-0003's W3C
propagation decision otherwise stands).

## Context

KRPC runs on self-owned Netty faces: gRPC rides grpc-java's `io.grpc.netty.NettyServerHandler`
(not `quarkus-grpc`), and HTTP rides KRPC's own `AbstractHttpHandler`. A consumer whose OTLP
pipeline is fully provisioned (OTel SDK + exporter on the classpath, endpoint configured) still
gets **zero traces from KRPC calls**, because Quarkus auto-instrumentation only wraps the faces it
owns — it never sees KRPC's transports. The missing layer is **span creation + inbound context
extraction** at KRPC's three entry/exit points.

ADR-0003 moved propagation to W3C Trace Context but deliberately stopped at pure forwarding: the
server parses the inbound `traceparent` into MDC (`traceId`/`spanId` for the log layout) and the
client forwards the unchanged `traceparent` on outbound calls. No spans were created, so there is
nothing to connect the inbound and outbound context into a real per-call trace tree.

ADR-0001 / North-Star NS-3 keep telemetry **backends** out of core (Kubernetes / Istio / the
consumer's Quarkus stack own them). The OpenTelemetry **API + instrumentation** is not a backend —
it is the integration seam. The SDK and exporter remain consumer-side.

## Decision

The framework creates spans, using the **OpenTelemetry API only**. Three instrumentation points:

1. **`OtelServerInterceptor`** (`rpc-server`, registered via `ServerBuilder.intercept`): extracts
   the inbound W3C context from the call metadata, starts a `SERVER` span (rpc semantic
   conventions: `rpc.system=grpc`, `rpc.service`, `rpc.method`, `rpc.grpc.status_code`), and makes
   it current on every listener callback — including `onHalfClose`, where KRPC runs the handler on
   the app executor (a virtual thread; ADR-0002). The handler and any outbound call it makes see
   the SERVER span as their current context.
2. **`OtelClientInterceptor`** (`rpc-client`, installed on the channel in `MethodCallProxyHandler`):
   starts a `CLIENT` span (child of the current context — the SERVER span when the call originates
   inside a handler) and injects `traceparent` into the outbound metadata.
3. **HTTP handler** (`http-server`, `AbstractHttpHandler`): extracts the inbound W3C context from
   the request headers (webhook/callback entry) and starts a `SERVER` span around `handle(...)`,
   current on the handler's virtual thread.

### API-only, hand-rolled (not `opentelemetry-grpc-1.6`)

Core depends on exactly one new artifact: `io.opentelemetry:opentelemetry-api` (which pulls only
`opentelemetry-context`). We hand-roll the interceptors on that API instead of adopting the
`io.opentelemetry.instrumentation:opentelemetry-grpc-1.6` library because:

- **NS-3 minimalism.** The instrumentation library is `-alpha` and pulls `instrumentation-api`,
  `instrumentation-api-incubator`, and `opentelemetry-semconv` into core. The RPC protocol surface
  is small (extract → start span → scope → inject → end); hand-rolling keeps core to a single
  stable API dependency and no alpha/incubator surface.
- **NS-7 native-image.** Fewer, non-alpha classes with no muzzle/bytecode machinery; rpc semantic
  attribute names are hardcoded (`KrpcOtel`) rather than pulled from the alpha semconv artifact.
- **Control over the ADR-0003 coexistence** (below), which the off-the-shelf interceptor does not
  give us.

### Explicit injection — never the JVM-global accessor

Core holds a `volatile OpenTelemetry` in `KrpcOtel`, `OpenTelemetry.noop()` until an integration
explicitly calls `KrpcOtel.install(OpenTelemetry)`. Core **never** reads the JVM-global
`OpenTelemetry` accessor. That was the original design and it is a landmine: on first read when
unset, the accessor does a synchronized init that (a) probes for an autoconfigure SDK via
`Class.forName` and, absent one, **pins the JVM-global to a no-op** — so any krpc call that arrives
before the consumer registers its SDK would permanently disable tracing AND break the consumer's own
later `buildAndRegisterGlobal()` (which throws once the global is set); or (b) with autoconfigure on
the classpath, reflectively boots an SDK we never asked for. Either way, reading the global from a
library on the hot path is wrong.

Wiring:

- **Quarkus** (`rpc-server-quarkus`): `RpcServiceExpose` (`@Startup`) injects an optional
  `Instance<OpenTelemetry>` and calls `install(...)` when resolvable; absent → stays no-op. `@Startup`
  runs after the container is initialized, so the bean (when the consumer ships `quarkus-opentelemetry`)
  is available. **Verified**: `examples:quickstart`'s `OtelServerSpanQuarkusTest` boots a real
  Quarkus app with `quarkus-opentelemetry` (test scope), asserts the bean is resolvable + `install`
  ran (`KrpcOtel.isNoop()==false`), and a real gRPC call exports a SERVER span. The only deferred
  check is full OTLP export to a real backend (consumer/iac concern, not core).
- **Spring** (`rpc-server-spring` `RpcServiceExposer#onApplicationEvent`, `rpc-client-spring`
  `RpcClientAutoConfigure#afterPropertiesSet`): install the context's `OpenTelemetry` bean via
  `ObjectProvider`/`getBeanProvider(...).ifAvailable(...)`; absent → stays no-op.
- **Plain netty** (no DI): the consumer calls `KrpcOtel.install(openTelemetry)` explicitly at
  startup. No ambient magic, no global pinning.

### Default-ON kill switch

A flag `rpc.otel.enabled` (system property) / `KRPC_OTEL` (env) gates registration; default **ON**.
Default-ON is safe: until an SDK is installed, `KrpcOtel` is a no-op — the tracer returns invalid
spans, the propagator injects/extracts nothing. Combined with the fast path below, a no-SDK consumer
sees **zero spans, zero header changes, zero cost beyond one volatile read + a reference compare per
call** (NS-4: byte-identical wire). The flag is resolved once at registration, so `KRPC_OTEL=false`
makes the interceptors byte-level absent (not even the volatile read).

### No-op fast path (per-call, allocation-free)

Each interceptor first checks `KrpcOtel.isNoop()` — a single `volatile` read of the installed
`OpenTelemetry` and a reference compare against the `OpenTelemetry.noop()` singleton, no allocation —
and short-circuits: the server interceptor returns `next.startCall(call, headers)` unwrapped, the
client interceptor returns `next.newCall(...)` unwrapped, and the HTTP handler skips
extraction/span/scope entirely. So the no-SDK path allocates nothing from OTel and leaves the call
untouched (no no-op span, no `Context.with`, no `Scope`, no listener wrappers).

The check is **per-call, not a build-time snapshot**, and it reads krpc's own volatile — never the
JVM global. Install ordering therefore does not matter and cannot corrupt anything: a call before
`install(...)` reads `noop()` and is an untraced no-op; a call after reads the installed SDK and
traces. If the SDK is installed after the very first calls, those early calls are untraced — an
unavoidable, negligible window; steady state traces correctly.

### Coexistence with ADR-0003 MDC forwarding

ADR-0003's MDC path is unchanged: the server still parses `traceparent` into MDC for the log
layout, and `PropagateTraceCall` still forwards the inbound `traceparent` verbatim on outbound
calls. The invariant is **exactly one `traceparent` on the wire in every mode**, enforced by
`KrpcOtel.METADATA_SETTER`, which *overwrites* (removeAll + put):

- **No SDK:** the OTel propagator is a no-op and injects nothing, so the MDC-forwarded
  `traceparent` survives unchanged — identical to pre-ADR-0006 behavior.
- **SDK present:** the client interceptor injects a fresh `traceparent` for the CLIENT span; the
  overwriting setter makes it supersede the MDC-forwarded (parent-level) value, so the downstream
  sees the correct new parent — still one header.

This is the clause of ADR-0003 that ADR-0006 amends: "the framework still creates no spans" no
longer holds. ADR-0003's decision to use W3C `traceparent`/`tracestate`, drop B3, and forward
`x-request-id` is untouched.

## Consequences

- **NS-3 intact.** No OTel SDK/exporter/backend in any core module's runtime classpath — only
  `opentelemetry-api` + `opentelemetry-context`. The SDK arrives from the consumer's Quarkus stack.
- **NS-4 intact.** No wire-format change: only `traceparent`/`tracestate` headers, and only when an
  SDK is active. A no-OTel-SDK consumer's wire is byte-identical.
- **NS-7.** No new open-world reflection; the API classes carry no reflection/config needs of their
  own (SDK native config is the consumer's Quarkus concern).
- **Consumers with a Quarkus OTel stack** now get connected traces across KRPC gRPC and HTTP hops
  with no application code — the gap that produced "OTLP provisioned, zero traces" is closed.
- **Version.** `opentelemetry-api` is pinned to the OTel SDK version shipped by the Quarkus 3.33
  LTS BOM (1.49.0) for zero compile/runtime skew. It is a plain `api` dependency, not a published
  platform constraint, so a consumer's BOM still wins the transitive version.
