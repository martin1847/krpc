# ADR-0007 — MCP auth boundary: resource-server semantics at the gateway, never an AS in core

Status: proposed
Date: 2026-07-30

## Context

The `/mcp` bridge (ADR-0004; aligned to MCP 2026-07-28 in PR#48) exposes an
agent-facing tool surface. The MCP authorization spec is now concrete about the
server side: an MCP server acts as an **OAuth 2.1 Resource Server** — RFC 9728
Protected Resource Metadata MUST be discoverable, tokens MUST be validated for
this server as audience (RFC 8707 resource binding on the client side), and a
server **MUST NOT pass through** the token it received to upstream APIs.
Enterprise deployments are converging on Enterprise-Managed Authorization
(ID-JAG via the corporate IdP; adopted by Anthropic/Microsoft/Okta).

krpc's standing principles already draw the line: infra belongs to the platform
(NS-3) and agent-surface auth/rate-limit sit at the gateway (NS-6, ADR-0004).
Today the bridge forwards `Authorization`/client-id into krpc's own credential
chain (`invokeWeb`), which validates for this service — there is no PRM
endpoint, no audience declaration, and nothing stops a deployment from wiring
the bridge to accept foreign tokens.

## Decision

1. **krpc core never becomes an Authorization Server.** No token issuance, no
   consent screens, no client registration in core — ever. (NS-3.)
2. **Resource-server obligations live at the gateway by default.** RFC 9728
   PRM publication, token validation, audience checking, rate limiting and
   audit sit in the deployment's gateway/mesh (Envoy AI Gateway-class), which
   fronts `/mcp` and `/agent/*`. krpc documents the contract; it does not
   re-implement it per service.
3. **Core provides exactly two small hooks, flag-gated, default OFF:**
   a. an **audience declaration** config (`rpc.server.mcp.audience`) surfaced
      through `server/discover` metadata, so gateways and clients can bind
      tokens to the right resource identifier;
   b. a **verified-identity passthrough contract**: the bridge accepts
      gateway-injected identity headers (already-validated principal + agent
      identity + on-behalf-of subject) into the credential chain, and logs the
      audit triple (human principal, agent identity, call context) on agent
      endpoints.
4. **Token passthrough stays prohibited.** The bridge never forwards inbound
   bearer tokens to upstream calls; upstream credentials are a separate,
   explicitly configured chain. This is already the de-facto behavior — this
   ADR makes it a tested invariant.
5. **Enterprise line**: deployments needing centralized control adopt
   Enterprise-Managed Authorization at the IdP/gateway; krpc requires no code
   change for it beyond (3a)/(3b).

## Consequences

- Positive: zero OAuth machinery in core (native-image friendly, NS-7); one
  documented contract instead of N per-service implementations; deployments
  compose with any spec-conformant gateway.
- Negative: a bare krpc server without a gateway offers no MCP auth beyond the
  existing credential chain — acceptable because agent-tool exposure is opt-in
  and default OFF (NS-6); the SPEC will state plainly that production MCP
  exposure requires a fronting gateway.
- Follow-ups (roadmap, not this ADR): implement (3a)/(3b) behind flags with
  negative probes; SPEC §12.2 gains an "auth boundary" subsection mirroring
  this decision; conformance test asserting the no-passthrough invariant.

## Alternatives rejected

- **Full RS in core** (validate JWTs, serve PRM from the bridge): duplicates
  gateway capability, drags an OAuth/JOSE dependency surface into a
  native-image-first core, and still needs a gateway for rate/audit — rejected
  on NS-3/NS-7.
- **Do nothing**: leaves audience undeclared and passthrough untested;
  rejected because the 2026-07-28 ecosystem makes both auditable MUSTs.
