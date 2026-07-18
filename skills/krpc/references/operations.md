# KRPC operations & consumer-ops — full reference

Deployment/runtime + release-consumption depth extracted from `SPEC.md` (§16.3–16.5 and
§15.6–15.7) to keep the contract lean. `SPEC.md` holds the authoring/calling contract and the
quick-ref config/port facts (§16.1–16.2) and points here. Section numbers below match their
original SPEC positions. Not part of the SPEC mirror (only `SPEC.md` is mirrored).

## Observability

### 16.3 Observability contract

Since **OTEL-001** (ADR-0006, amending ADR-0003) the framework **creates spans** on its own
faces via the OpenTelemetry **API** (no SDK in core; a no-SDK consumer sees zero spans, zero
wire change — NS-3/NS-4).

- **Spans:** gRPC **SERVER** — `OtelServerInterceptor`, registered globally
  (`RpcServerBuilder.java:145-146`), named `{full/method}` (`OtelServerInterceptor.java:48-54`);
  gRPC **CLIENT** — `OtelClientInterceptor` on the channel (`MethodCallProxyHandler.java:61-64`),
  injects `traceparent` on egress; HTTP **SERVER** — `AbstractHttpHandler` extracts W3C context
  and spans `handle(...)` (`AbstractHttpHandler.java:181-236`).
- **MDC ↔ span.** ADR-0003's MDC path coexists: the server parses inbound `traceparent` into
  MDC (`traceId`/`spanId` for the log pattern — §11) and the OTel span shares that same W3C
  context, made current on the handler VT (`OtelServerInterceptor.java:75`). Invariant:
  **exactly one `traceparent` on the wire** in every mode (ADR-0006 "Coexistence").
- **W3C only.** B3 (`x-b3-*`) is neither read nor emitted (ADR-0003) — wire change vs 1.0.0;
  a B3-only peer shares no trace context.
- **Symptom: empty `traceId= spanId=`.** The layout prints these only when `TraceMeta.parse`
  succeeds (`ServerContext.java:96-105`); `parse` returns `null` for an **absent OR malformed**
  header (`TraceMeta.java:39-47`). So empty IDs = no *validly parsed* W3C `traceparent`, not
  necessarily none arrived — a present header is still stored raw (`MDC_TRACEPARENT`,
  `ServerContext.java:99`). **Inspect the raw header first** to tell "no header" from
  "malformed/non-W3C" (e.g. B3-only upstream).

**Zero-trace fault tree** ("OTLP provisioned but no traces") — walk in order:

| # | layer | check | owner |
| --- | --- | --- | --- |
| 1 | app produces spans | OTel SDK installed? Core is API-only, **no-op until `KrpcOtel.install(...)`** (`isNoop()`→pass-through, `OtelServerInterceptor.java:40-42`); confirm the consumer stack (e.g. `quarkus-opentelemetry`) so the bean injects at `@Startup` (ADR-0006). No SDK ⇒ no spans, correctly | app/code (anchored) |
| 2 | egress allowed | observability-namespace **NetworkPolicy** permits pod→OTLP egress? default-deny silently drops exporter traffic | platform (NS-3) |
| 3 | receiver accepts | OTLP **auth / org header** (e.g. tenant header) correct? a rejected export looks identical to "no traces" | platform (NS-3) |

### 16.4 Unknown-method calls produce no span

**[inference, not test-pinned].** An unknown gRPC method creates **no krpc SERVER span**:
`OtelServerInterceptor` is a global interceptor (`RpcServerBuilder.java:145-146`) deriving its
span from a **resolved** method descriptor (`OtelServerInterceptor.java:44-54`), and grpc-java
invokes a global interceptor only *after* a successful lookup (closing an unregistered method
`UNIMPLEMENTED` at the transport layer first) — but that dispatch step has **no krpc test and
no pinned grpc-java source/version** here, so treat it as inference. The HTTP `/agent/invoke` path
differs: the endpoint is a registered handler, so an unknown *service in the body* is a
normally-spanned request that just misses the `WebMethodRegistry` lookup
(`WebMethodRegistry.java:36-40`) → `code:5`.

### 16.5 Release & rollback discipline (runbook)

- **Image-baked changes need a manual bump.** Anything in the image (code, or build-baked
  config — §16.1) reaches an environment only via a deliberate **version bump + rebuild** (set
  `version` + `changelog.md` first, §12) — no hot-reload; runtime config (§16.1) is the
  no-rebuild escape hatch.
- **Rollback respects the schema floor.** Rolling *back* is safe only to the **lowest image
  version compatible with the currently-applied DB schema**: forward Flyway migrations aren't
  auto-reverted, so a schema at version *N* pins the floor to the first image that understands
  *N*; below it, the old code hits columns/tables it can't read. Establish the floor
  **before** rolling back. (Consumer/`ext-mybatis`-side; persistence posture ADR-0002 / NS-5,
  §12.6.)


## Consuming releases

### 15.6 Consuming a release-candidate (`-rc`) artifact

An `-rc` (e.g. `1.1.1-rc1`) is a producer pre-release for verification. Routes by scope:

| route | when | how |
| --- | --- | --- |
| **mavenLocal** | same machine (dev loop) | producer `publishToMavenLocal`; consumer `mavenLocal()` (`build.gradle:16`) |
| **vendored file-repo** | shared `verify-bump` branch | commit into a repo-relative Maven file-repo + point a `maven { url … }` at it (`MAVEN_REPO` scaffold, `build.gradle:17-19`, `gradle.properties:3`) |
| **Nexus** | long-term / cross-team | publish `-rc` to a Nexus snapshot/staging repo the team resolves |

`-rc` **never shadows a Central GA**: Maven sorts the pre-release qualifier *below* the final
(`1.1.1-rc1` < `1.1.1`), so once GA lands, highest-wins prefers it. (Central publish =
`gradle/publish-central.sh`, §12.)

> Consumer-side operational guidance (standard Maven semantics); only the `mavenLocal()` /
> `MAVEN_REPO` scaffold (`build.gradle:16-19`) is anchored in-repo. Nexus has no in-repo anchor.

### 15.7 Living example — the consumer smoke script

The LH umbrella owns a runnable end-to-end smoke script `scripts/staging-smoke.sh` (**not
vendored here**): points at a staging service, runs `discover → invoke` (or `rpcurl -a <app>
-d '{…}'`) against a known `@UnsafeWeb` method, asserts the `RpcResult` envelope (`code:0`,
expected `data`). Reference for a real call sequence — read it in the LH umbrella, don't copy
it into krpc.
