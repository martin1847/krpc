# Active Roadmap

## NATIVE-001: io.grpc Version Alignment For Quarkus Native Consumers

Status: active

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

Status: active  (P0 delivered on `dev`, security-reviewed; P1 not started)

Capability: Expose KRPC's runtime self-description to AI agents (discover → call)

Components:

- `rpc-common` (`RpcMetaService`, `ApiMeta`), `rpc-server` (`RpcMetaServiceImpl`)
- `rpc-client` (`GeneralizeClient`)
- `http-server` (HTTP discover/invoke endpoints)
- `rpc-api` (`@UnsafeWeb` `agentTool` attribute, `@Doc`)
- new runtime MCP module (P1)

ADR: ADR-0004

Phases (sequenced P0 → P1):

- **P0 (DONE, on `dev`)** — HTTP `listApis()` discovery endpoint + generic HTTP
  invoke endpoint (HTTP analogue of `GeneralizeClient`); `WebMethodRegistry` +
  `UnaryMethod.invokeWeb`. Hidden services double-filtered, credential not bypassed,
  filter chain single-pass. Security review APPROVE (0 blocking). JVM-mode only —
  native reflection-config for the new handlers not yet added.
- **P1** — Runtime MCP server module generated from live `ApiMeta`, Streamable
  HTTP transport, **feature switch default OFF**. Tools = methods opted in via
  `@UnsafeWeb(agentTool=true)` (default `false`).

Acceptance Criteria:

- An HTTP client can list services + schemas and invoke a method without a gRPC stack (P0).
- With the MCP switch ON, an MCP client sees each `agentTool=true` method as a tool
  whose `inputSchema`/`outputSchema` derive from the DTO type tree, constraints,
  `@Doc`, and `RpcResult<T>` (P1).
- MCP module off by default adds no runtime surface.
- No service registry/discovery/LB added (stays within ADR-0001).
- Authentication remains gateway-side this phase.
