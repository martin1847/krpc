# Module: Platform Integrations

## FOR

- Spring client/server auto-configuration and scanning.
- Quarkus and GraalVM native-image support.
- HTTP gateway behavior that the external `rpcurl` CLI (`krpc-crates`, Rust) rides over.
- Code generation and test/demo applications that validate supported integration paths.

## NOT FOR

- Core RPC semantics that belong in `rpc-common`, `rpc-client`, or `rpc-server`.
- Production deployment policy.
- Long-lived business applications.

## Components

- `rpc-client-spring`
- `rpc-server-spring`
- `rpc-server-quarkus`
- `http-server`
- `test-rpc-gen`
- `test-server`
- `test-server-spring`
- `test-jwks`

## Evolution

### Active

- Document integration ownership so framework support does not drift into core runtime modules. Status: active
- Keep Spring, Quarkus, GraalVM native-image, HTTP gateway, and test/demo behavior aligned with the JDK 21 baseline. Status: active
- Close the native-image consumer gaps found downstream 2026-06: grpc version alignment (NATIVE-001, krpc roadmap) and server-side provider registration upstreamed into ext-rpc (NATIVE-002, workspace roadmap). Consumer-facing truth: `SPEC.md` §13. Status: active

### Deferred / Obsolete

- Separate test/demo applications into another repository. Status: deferred - keeping them local currently supports regression validation.
- In-repo `rpcurl` CLI (Java, then Dart). Status: obsolete - both were retired; the CLI now lives externally in `krpc-crates` (Rust), validated against this repo only via CI (`native-smoke.yml`).
