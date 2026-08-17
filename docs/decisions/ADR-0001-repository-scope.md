# ADR-0001: Repository Scope And Boundaries

Status: proposed

Date: 2026-05-30

## Context

KRPC is a multi-module RPC framework. The README describes its goal as making RPC service development feel like writing normal language-level interfaces while using gRPC/HTTP/2 underneath. The repository currently contains API annotations and result models, shared serialization/context/filter code, Java client and server runtimes, Spring/Quarkus integrations, an HTTP gateway, generated protocol artifacts, and test applications. The `rpcurl` CLI client now lives externally in `krpc-crates` (Rust); this repo no longer hosts an rpcurl implementation (the in-repo Java and Dart rpcurl clients were both retired).

The repository needs a lightweight source-of-truth structure so future AI-assisted and human changes can distinguish stable architectural decisions, module ownership, and active work.

## Decision

This repository is FOR:

- Interface-first KRPC service contracts built around Java APIs and DTOs.
- Java client/server runtime implementation over gRPC and HTTP/2.
- Framework integrations, native-image support, HTTP gateway support, code generation, and local test/demo tooling needed to operate and validate KRPC.

This repository is NOT FOR:

- Owning service discovery, load balancing, telemetry, or mesh behavior that is delegated to Kubernetes, Istio, or deployment infrastructure.
- Becoming a generic REST framework or unrelated HTTP application framework.
- Hosting production service implementations outside test/demo modules.
- Replacing external language ecosystems beyond generated clients and protocol/tooling support.

The initial governance source-of-truth layout is:

- `docs/decisions/` for ADRs.
- `docs/modules/` for module boundaries and evolution notes.
- `docs/roadmap/active-roadmap.md` for tracked plans.
- `.ai/ACTIVE_CONTEXT.md` for short-lived working context.

## Consequences

Future architectural changes should update ADRs and relevant module docs instead of living only in code or chat history.

The first version of this ADR is `proposed` because the module boundaries are inferred from the current repository and README, not yet explicitly accepted by maintainers.

Roadmap and module evolution items should be marked with roadmap/evolution statuses, not ADR statuses.
