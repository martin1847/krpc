# Module: Platform Integrations

## FOR

- Spring client/server auto-configuration and scanning.
- Quarkus and GraalVM native-image support.
- HTTP gateway behavior and RPC CLI tooling.
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
- `rpcurl`
- `test-rpc-gen`
- `test-server`
- `test-server-spring`
- `test-jwks`

## Evolution

### Active

- Document integration ownership so framework support does not drift into core runtime modules. Status: active
- Keep Spring, Quarkus, GraalVM native-image, HTTP gateway, and test/demo behavior aligned with the JDK 21 baseline. Status: active

### Deferred / Obsolete

- Separate test/demo applications into another repository. Status: deferred - keeping them local currently supports regression validation.
