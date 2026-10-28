# ADR-0004: Agent-Friendly Introspection And MCP Surface

Status: accepted

Date: 2026-06-20

## Context

KRPC already exposes complete service self-description at runtime:
`RpcMetaService.listApis()` (auto-registered on every server) returns `ApiMeta`
— services, method signatures, full DTO type trees (incl. generics), `@Doc`
documentation, validation constraints, and `@Doc.ErrorCode` entries — and
`GeneralizeClient` can invoke any method by `app/Service/method` + JSON without a
compiled interface. The `discover → call` building blocks therefore already exist;
they are simply not exposed through the protocols AI agents speak.

The agent ecosystem has standardized on the Model Context Protocol (MCP, now under
the Linux Foundation): tools described by JSON-Schema `inputSchema`/`outputSchema`
over Streamable HTTP. Existing gRPC→MCP bridges reverse-engineer tool definitions
from `.proto`; KRPC's richer `ApiMeta` (semantic `@Doc` + constraints) can produce
higher-quality tool definitions as a transform, not hand-coding.

Per ADR-0001, service discovery / load balancing / infra remain out of scope.
Agent-friendliness must live in the **introspection and schema layer**, not infra.

## Decision

Make KRPC agent-friendly by exposing its existing runtime metadata through agent
protocols, in phases (see roadmap AGENT-001):

1. **P0 — HTTP discover→call loop.** Expose `RpcMetaService.listApis()` over HTTP
   (it is currently an internal hidden service, unreachable by HTTP agents), and a
   generic HTTP invoke endpoint (the HTTP analogue of `GeneralizeClient`). Agents
   can then list services, read schemas, and call — over plain HTTP, no gRPC stack.
2. **P1 — Native MCP server.** A **runtime module** that serves an MCP server
   generated from live `ApiMeta`: each opted-in method → an MCP tool, `inputSchema`
   from the DTO type tree + validation constraints, `outputSchema` from
   `RpcResult<T>`, descriptions from `@Doc`, errors from `@Doc.ErrorCode`. Transport:
   Streamable HTTP. It is **gated by a feature switch, default OFF.**

Cross-cutting decisions:

- **Tool opt-in is an attribute of `@UnsafeWeb`, default `false`.** A method/service
  becomes an agent tool only when explicitly opted in (e.g. `@UnsafeWeb(agentTool=true)`).
  Agent exposure is a deliberate subset of web exposure, not implied by it.
- **Authentication AND rate-limiting are handled at the gateway, not in KRPC core**
  for now. The HTTP agent endpoints (`/agent/discover`, `/agent/invoke`) carry no
  in-core auth or throttling: `/agent/discover` exposes the full schema of every
  `@UnsafeWeb` service to any caller, and `/agent/invoke` reaches every
  `requireCredential=false` `@UnsafeWeb` service. A deployment MUST place these two
  paths behind a gateway that enforces auth and rate limits. `@UnsafeWeb(requireCredential=true)`
  services still run the in-core credential check on the invoke path. MCP's OAuth 2.1
  mapping is likewise deferred to the gateway.
- **Stay within ADR-0001 boundaries:** no service registry/discovery/LB. Only the
  introspection + schema-expression layer.

Later phases (P2, not committed): OpenAPI 3.1 export from the same `ext-rpc-gen` IR;
structured/semantic error catalog in tool schemas; richer `@Doc` (examples, MCP
read-only/destructive annotations).

## P1 scope revision (2026-07-03, status stays accepted)

P1 is re-scoped from "a **standalone runtime MCP module**" (Decision §2 above) to a
**thin bridge on the existing P0 HTTP surface**. Rationale:

- **MCP is now the de-facto standard** (Linux Foundation; Spring AI 2.0 ships
  annotation→tool). The differentiator is no longer "have an MCP server" but krpc's
  combination: **no proto + rich `ApiMeta` semantics (`@Doc` + jakarta constraints) +
  native zero-glue.** A separate module would duplicate the transport/host that P0
  already owns.
- **The bridge rides the existing `http-server` netty host** (same process as
  `/agent/*`): a new `POST /mcp` handler generates `tools/list` from the live
  `ApiMeta` and dispatches `tools/call` through the **same `WebMethodRegistry.invokeWeb`
  path as `/agent/invoke`** — credential check NOT bypassed, hidden services still
  double-filtered. No new module, no new Central artifact.
- **No third-party MCP SDK dependency.** Evaluated the official
  `io.modelcontextprotocol.sdk:mcp` (2026-07): its core is **Project Reactor**
  (reactive-streams) with a synchronous facade, and its server transports are
  **servlet / spring-webmvc / spring-webflux** — there is no raw-netty transport.
  Embedding it would force a servlet container or a custom transport against a
  reactive core, plus a heavy Reactor runtime + native-image reflection burden —
  contradicting the "native zero-glue" moat. The protocol face we need is narrow
  (`initialize`, `tools/list`, `tools/call`, JSON-RPC 2.0 over HTTP POST), so it is
  **hand-written to the MCP spec (2025-06-18)** with Jackson (already a dependency).
- **Transport: Streamable HTTP, JSON-response mode, no SSE.** The spec makes SSE
  optional — for a JSON-RPC *request* the server MAY return a single
  `application/json` object (the client MUST support this), and GET MAY return 405.
  krpc's tools are unary request/response, so the bridge returns one JSON object per
  POST and does not open SSE streams (revisit only if a streaming tool shape appears).
- **`agentTool` strict subset gate** (Decision cross-cut, Wave 2 not-yet-built): MCP
  `tools/list` contains **only** `@UnsafeWeb(agentTool=true)` methods; `/agent/discover`
  keeps its current web-filtered view unchanged. The two surfaces are deliberately
  distinct — agent-tool exposure is a subset of web exposure.
- **Flag `rpc.server.mcp.enabled` (env `KRPC_MCP`), default OFF** = byte-level zero new
  surface. ON with no `agentTool` method = empty `tools` list (valid).

## Consequences

- **Positive:** turns an existing asset (runtime `ApiMeta` + `GeneralizeClient`)
  into a differentiated capability — "write a Java interface, get an MCP tool" —
  with minimal new code and no wire-protocol change. MCP server is opt-in and
  off by default, so it adds zero surface to deployments that don't enable it.
- **Negative / risks:** exposing introspection + a generic invoke endpoint widens
  the attack surface; the `agentTool` opt-in + default-OFF switch + gateway-side
  auth are the mitigations. Tool curation matters — do not map every method 1:1.
- **Future evolution:** `@Doc` may grow LLM-oriented fields; OpenAPI export can
  reuse the MCP schema transform; auth may later be offered in-core if gateway-only
  proves insufficient.
