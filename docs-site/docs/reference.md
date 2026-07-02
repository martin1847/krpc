---
sidebar_position: 2
title: Reference
---

# Reference

This site is intentionally thin: it links to the canonical documents in the
repository rather than duplicating them. When this page and a linked document
differ, the **linked document wins**.

## Handbook

- **[SPEC.md](https://github.com/martin1847/krpc/blob/dev/SPEC.md)** — the
  authoring handbook: method contract, `RpcResult` envelope, soft/hard error
  model, DTO rules, `@RpcService` naming, `@UnsafeWeb`, validation, auth/JWKS,
  and native-image (§13).

## Lifecycle & Security

- **[Support Policy](https://github.com/martin1847/krpc/blob/dev/docs/support-policy.md)**
  — version/support lifecycle. SPEC §13.1 is the canonical version matrix.
- **[SECURITY.md](https://github.com/martin1847/krpc/blob/dev/SECURITY.md)** —
  how to report a vulnerability (GitHub Private Vulnerability Reporting),
  response targets, disclosure window, and scope.
- **[Changelog](https://github.com/martin1847/krpc/blob/dev/changelog.md)** —
  release notes.

## Governance

- **[Documentation Index](https://github.com/martin1847/krpc/blob/dev/docs/INDEX.md)**
  — the map of the repository source of truth.
- **[ADRs](https://github.com/martin1847/krpc/tree/dev/docs/decisions)** —
  architecture decision records (repository scope, JDK 21 / virtual threads,
  W3C trace context, agent-friendly introspection).
- **[Contributing](https://github.com/martin1847/krpc/blob/dev/CONTRIBUTING.md)**
  — DCO sign-off, PR flow, build commands.

## Clients

Generated or companion clients exist for Dart, TypeScript, Python, Go/k6, Java,
and [`rpcurl`](https://github.com/martin1847/krpc-crates/).
