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
