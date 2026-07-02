# Documentation Index

This index maps the repository source of truth for KRPC.

## Decisions

- [ADR-0001: Repository Scope And Boundaries](decisions/ADR-0001-repository-scope.md)
- [ADR-0002: JDK 21 And Virtual Threads](decisions/ADR-0002-jdk21-virtual-threads.md)
- [ADR-0003: W3C Trace Context Propagation](decisions/ADR-0003-w3c-trace-context.md)
- [ADR-0004: Agent-Friendly Introspection And MCP Surface](decisions/ADR-0004-agent-friendly-introspection.md)

## Modules

- [API Contracts](modules/api-contracts.md)
- [Runtime Core](modules/runtime-core.md)
- [Platform Integrations](modules/platform-integrations.md)

## Roadmap

- [Active Roadmap](roadmap/active-roadmap.md)

## Handbook

- [Development Spec](../SPEC.md) — authoring handbook for humans and coding
  agents (method contract, errors, auth, native image §13)
- [Support Policy](support-policy.md) — version/support lifecycle; SPEC §13.1 is
  the canonical version matrix

## Plans

- [Dependency Bump Plan](plan/2026-05-30-dependency-bump.md)

## AI Context

- [Active Context](../.ai/ACTIVE_CONTEXT.md)

## Traceability

| Capability | Component | ADR | Roadmap |
| --- | --- | --- | --- |
| Interface-first RPC contracts | `rpc-api`, `test-api`, `proto` | ADR-0001 | GOV-001 |
| Java client/server runtime over gRPC and HTTP/2 | `rpc-common`, `rpc-client`, `rpc-server` | ADR-0001 | GOV-001 |
| Framework, native-image, gateway, and tooling integrations | `rpc-client-spring`, `rpc-server-spring`, `rpc-server-quarkus`, `http-server`, `rpcurl`, `test-rpc-gen` | ADR-0001 | GOV-001 |
| JDK 21 baseline and virtual-thread runtime feature | `rpc-server`, `rpc-client`, `http-server`, integration modules | ADR-0002 | N/A |
| Agent-friendly introspection (HTTP discover→call, runtime MCP surface) | `rpc-common`, `rpc-server`, `rpc-client`, `http-server`, `rpc-api` | ADR-0004 | AGENT-001 |
| Quarkus native-image consumer path (grpc alignment, provider registration) | `gradle.properties`, `rpc-server-quarkus`, `SPEC.md` §13 (+ `ext-rpc`, workspace NATIVE-002) | N/A | NATIVE-001 |
