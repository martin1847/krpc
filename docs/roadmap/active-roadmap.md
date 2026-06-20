# Active Roadmap

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
