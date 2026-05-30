# Module: Runtime Core

## FOR

- Shared serialization, context, filter, metadata, and utility behavior.
- Java client runtime behavior and client-side invocation support.
- Java server runtime behavior, invocation, validation, filters, health, and JWS verification.

## NOT FOR

- Framework-specific auto-configuration.
- Demo service business logic.
- External infrastructure concerns such as service discovery, mesh telemetry, or load balancing.

## Components

- `rpc-common`
- `rpc-client`
- `rpc-server`

## Evolution

### Active

- Keep runtime boundaries explicit across common, client, and server modules. Status: active
- Maintain JDK 21 and virtual-thread runtime support. Status: active

### Deferred / Obsolete

- Introduce a new shared runtime abstraction layer. Status: deferred - current module split is adequate until duplication or dependency pressure is demonstrated.
