# Active Roadmap

## NATIVE-001: io.grpc Version Alignment For Quarkus Native Consumers

Status: completed (shipped in krpc 1.0.3, 2026-07-02 — Option A: grpcVersion tracks the
Quarkus LTS BOM; SPEC §13.1 matrix is the consumer contract; no consumer force needed)

Capability: krpc artifacts consumable in Quarkus native builds without a
consumer-side grpc version force

Components:

- `gradle.properties` (`grpcVersion=1.82.0`)
- published POM dependency metadata (`tech.krpc:*`)
- `SPEC.md` §13.1 (consumer workaround — delete when this completes)

ADR: N/A (evidence: downstream native builds abort during Initializing —
Quarkus GraalVM substitution `Target_io_grpc_ServiceProviders.loadAll` does not
match grpc 1.82.0 when krpc's transitive pin overrides the Quarkus BOM's 1.79.0;
verified 2026-06 on 8 downstream Quarkus 3.33.2 services)

Acceptance Criteria:

- A documented grpc version policy: either align `grpcVersion` with the current
  Quarkus LTS BOM, or declare the supported Quarkus↔krpc↔grpc matrix and mark
  `io.grpc:*` so the consumer BOM wins by default (e.g. compatible-range /
  runtime-provided scope), with the tradeoff recorded.
- A Quarkus 3.33.x consumer can native-build without a root-build
  `resolutionStrategy` force on `io.grpc:*`.
- Wire compatibility across the supported grpc range is stated in `SPEC.md`.
- `SPEC.md` §13.1 workaround section removed.

## NATIVE-003: io_uring transport revisit

Status: deferred

Capability: adopt Netty's io_uring transport for krpc's native server if and when
it outperforms NIO on a representative workload

Components:

- `rpc-server` (`IoUringTransport`, `KRPC_IOURING` flag), native metadata
  (`test-server-iouring` reflect/jni/resource configs)
- `SPEC.md` §13.5 (evaluation note)
- eval branch `feat/iouring-eval` (flag-gated PoC)

ADR: N/A (evidence: 2026-07 PoC benchmarked 5–6% SLOWER than NIO on krpc's
small-message unary path, aarch64 containerized — workspace
`docs/orchestration/IOURING-001_{RESEARCH,BENCH}_omp.md`)

Gated on: Quarkus 4 / Vert.x 5 (Netty 4.2 graduated `io.netty.channel.uring`
transport with in-jar native metadata — removes the archived-incubator artifact
and hand-authored metadata this PoC required).

Acceptance Criteria:

- Re-benchmark on a high-connection-count / streaming workload (io_uring's
  syscall-bound win case), not the small-message unary path.
- Adopt only if it wins there; the flag stays default OFF regardless.

## GOV-001: Establish Repository Governance Baseline

Status: active

Capability: Repository governance and traceability

Components:

- `docs/INDEX.md`
- `docs/decisions/ADR-0001-repository-scope.md`
- `docs/modules/api-contracts.md`
- `docs/modules/runtime-core.md`
- `docs/modules/platform-integrations.md`
- `.ai/ACTIVE_CONTEXT.md`
- `AGENTS.md`
- `CLAUDE.md`

ADR: ADR-0001

Acceptance Criteria:

- `docs/INDEX.md` points to decisions, modules, roadmap, and AI context.
- ADR-0001 records repository scope and explicit non-goals.
- At least one module doc defines FOR and NOT FOR boundaries.
- Future work can attach to a roadmap item, ADR, or module evolution section.
- Maintainers review inferred boundaries and either accept ADR-0001 or update it.

## AGENT-001: Agent-Friendly Introspection And MCP Surface

Status: active  (P0 + P1 both delivered on `dev` and security-reviewed; P1 MCP bridge merged to
`dev` as PR #14, `9be92e6`. Remaining scope = uncommitted P2 ideas only.)

Capability: Expose KRPC's runtime self-description to AI agents (discover → call)

Components:

- `rpc-common` (`RpcMetaService`, `ApiMeta`), `rpc-server` (`RpcMetaServiceImpl`)
- `rpc-client` (`GeneralizeClient`)
- `http-server` (HTTP discover/invoke endpoints)
- `rpc-api` (`@UnsafeWeb` `agentTool` attribute, `@Doc`)
- `rpc-server-quarkus` MCP bridge (`McpHandler`/`McpSchema`/`McpToolRegistry`, P1 — a
  thin bridge on the P0 http-server host, not a separate module; ADR-0004 revised)

ADR: ADR-0004

Phases (sequenced P0 → P1):

- **P0 (DONE, on `dev`; bean-removal + native gaps closed on `feat/mcp-bridge`)** —
  HTTP `listApis()` discovery endpoint + generic HTTP invoke endpoint (HTTP analogue
  of `GeneralizeClient`); `WebMethodRegistry` + `UnaryMethod.invokeWeb`. Hidden
  services double-filtered, credential not bypassed, filter chain single-pass.
  Security review APPROVE (0 blocking).
  - **Bean-removal caveat: FIXED.** `AgentDiscoverHandler`/`AgentInvokeHandler` now
    carry `@io.quarkus.arc.Unremovable`, so they survive Arc's default
    `remove-unused-beans=all` and the endpoints are reachable in a default consumer
    (JVM + native), no consumer action. Container-level `@QuarkusTest` added.
  - **Native: DONE.** `AgentInvokeRequest` reflection-config added; native build +
    boot verified (Mandrel 25/JDK25) with the agent endpoints reachable.
- **P1 (DELIVERED on `dev`, merged as PR #14 `9be92e6`)** — MCP Streamable
  HTTP bridge (`POST /mcp`, spec 2025-06-18, JSON-RPC 2.0), **not** a standalone
  module: a thin bridge on the P0 http-server host (ADR-0004 revised). `tools/list`
  generated from live `ApiMeta`; `tools/call` via the same `invokeWeb` path
  (credential not bypassed). Tools = `@UnsafeWeb(agentTool=true)` subset only.
  **Feature switch `rpc.server.mcp.enabled` (env `KRPC_MCP`) default OFF** = zero new
  surface. No third-party MCP SDK. Verified with real MCP clients (initialize via a
  `@modelcontextprotocol/sdk` client script; tools/list + tools/call via the official
  `@modelcontextprotocol/inspector` CLI): JVM +
  native (Mandrel 25/JDK25); initialize + tools/list byte-identical across both,
  tools/call differs only in the runtime timestamp — verbatim transcripts in
  `docs/mcp-transcripts/{jvm,native}.txt`; GET/mcp→405, unsupported
  MCP-Protocol-Version→400.

Acceptance Criteria:

- An HTTP client can list services + schemas and invoke a method without a gRPC stack (P0).
- With the MCP switch ON, an MCP client sees each `agentTool=true` method as a tool
  whose `inputSchema`/`outputSchema` derive from the DTO type tree, constraints,
  `@Doc`, and `RpcResult<T>` (P1).
- MCP module off by default adds no runtime surface.
- No service registry/discovery/LB added (stays within ADR-0001).
- Authentication remains gateway-side this phase.

## OTEL-001: OpenTelemetry Span Creation On KRPC's Faces

Status: active

Capability: KRPC creates SERVER/CLIENT spans and extracts/injects W3C trace context on its own
Netty faces (gRPC + HTTP), so a consumer with a provisioned OTLP pipeline gets connected traces
across KRPC hops without application code. Span creation via the OpenTelemetry **API only** — no
SDK/exporter in core (the SDK arrives from the consumer's Quarkus stack; ADR-0001 / NS-3).

Components:

- `rpc-common` (`KrpcOtel` — flag, tracer, gRPC metadata getter/setter, rpc/http attribute keys),
  new `opentelemetry-api` dependency
- `rpc-server` (`OtelServerInterceptor`, registered in `RpcServerBuilder`)
- `rpc-client` (`OtelClientInterceptor`, installed in `MethodCallProxyHandler`)
- `http-server` (`AbstractHttpHandler` SERVER-span extraction)
- kill switch `rpc.otel.enabled` / env `KRPC_OTEL`, default ON (no-op without an SDK)

ADR: ADR-0006 (amends ADR-0003's "no spans" clause)

Acceptance Criteria:

- Inbound `traceparent` → SERVER span → CLIENT span → outbound `traceparent`, one header, correct
  parent chain across a real in-process hop (test-proven).
- No behavior change without an OTel SDK (byte-identical wire; parity test).
- No OTel SDK/exporter in any core module's runtime classpath (NS-3).
- Native build + boot with the OTel surface, no reflection errors (NS-7).
