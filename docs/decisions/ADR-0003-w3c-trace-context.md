# ADR-0003: W3C Trace Context Propagation

Status: accepted

Amended by: ADR-0006 (2026-07-16). ADR-0006 supersedes the "the framework creates no spans"
clause below (span creation now joins the framework via the OTel API). The W3C propagation
decision in this ADR — single `traceparent`/`tracestate`, drop B3, forward `x-request-id` — still
stands, and the MDC forwarding path continues to coexist with span creation (one `traceparent` on
the wire; see ADR-0006 "Coexistence with ADR-0003 MDC forwarding").

Date: 2026-06-20

## Context

KRPC propagates distributed-trace context across RPC hops. The framework does not
create spans; it only forwards the inbound trace context from a server call to any
outbound client call made on the same thread (via MDC).

The original implementation used B3 multi-header propagation (`x-b3-traceid`,
`x-b3-spanid`, `x-b3-parentspanid`, `x-b3-sampled`, `x-b3-flags`). B3 is a Zipkin
convention; the industry-standard, vendor-neutral format is now W3C Trace Context,
which carries the same information in a single `traceparent` header plus an optional
`tracestate` header. W3C is the default propagator for OpenTelemetry and the mesh /
gateway layers KRPC delegates telemetry to (see ADR-0001).

Maintaining B3 keeps KRPC on a non-standard, multi-header format and complicates
interop with W3C-based infrastructure.

## Decision

KRPC propagates trace context using W3C Trace Context:

- Read and write a single `traceparent` header
  (`version-traceid(32hex)-spanid(16hex)-flags(2hex)`).
- Forward the optional `tracestate` header verbatim when present.
- Continue forwarding `x-request-id` for request correlation.
- Drop all B3 headers entirely. KRPC neither emits nor reads `x-b3-*`.

Behavior remains pure propagation: the server parses the inbound `traceparent`,
exposes `traceId` / `spanId` to the log layout via MDC, and the client forwards the
unchanged `traceparent` (plus `tracestate` / `x-request-id`) on outbound calls. The
framework still creates no spans. <!-- Superseded by ADR-0006: the framework now creates
SERVER/CLIENT spans via the OTel API; this MDC forwarding coexists (one traceparent on the wire). -->

## Consequences

This is a wire-contract change.

- All sibling clients (TypeScript, Python, Dart, Rust, C++) must move to W3C
  Trace Context to keep cross-service traces connected. A B3-only peer will no
  longer share trace context with a KRPC service, and vice versa.
- This is incompatible with the already-published 1.0.0 release, which used B3.
  Mixing a B3 peer with a W3C peer silently breaks trace continuity (no error,
  just an orphaned trace). This change must ship under a new, incompatible
  version, not as a patch on 1.0.0.

Code that previously read the B3 MDC keys (`x-b3-*`) must read `traceparent`
instead.
