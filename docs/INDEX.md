# Documentation Index

This index maps the repository source of truth for KRPC.

## Decisions

- [ADR-0001: Repository Scope And Boundaries](decisions/ADR-0001-repository-scope.md)
- [ADR-0002: JDK 21 And Virtual Threads](decisions/ADR-0002-jdk21-virtual-threads.md)

## Modules

- [API Contracts](modules/api-contracts.md)
- [Runtime Core](modules/runtime-core.md)
- [Platform Integrations](modules/platform-integrations.md)

## Roadmap

- [Active Roadmap](roadmap/active-roadmap.md)

## AI Context

- [Active Context](../.ai/ACTIVE_CONTEXT.md)

## Traceability

| Capability | Component | ADR | Roadmap |
| --- | --- | --- | --- |
| Interface-first RPC contracts | `rpc-api`, `test-api`, `proto` | ADR-0001 | GOV-001 |
| Java client/server runtime over gRPC and HTTP/2 | `rpc-common`, `rpc-client`, `rpc-server` | ADR-0001 | GOV-001 |
| Framework, native-image, gateway, and tooling integrations | `rpc-client-spring`, `rpc-server-spring`, `rpc-server-quarkus`, `http-server`, `rpcurl`, `test-rpc-gen` | ADR-0001 | GOV-001 |
| JDK 21 baseline and virtual-thread runtime feature | `rpc-server`, `rpc-client`, `http-server`, integration modules | ADR-0002 | N/A |
