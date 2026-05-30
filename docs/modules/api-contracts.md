# Module: API Contracts

## FOR

- Defining KRPC service contracts through Java interfaces and annotations.
- Providing shared API result and pagination models used by services and clients.
- Maintaining generated or reference protocol definitions that describe internal wire messages.
- Hosting test API declarations used to validate framework behavior.

## NOT FOR

- Implementing transport runtime behavior.
- Owning business service implementations.
- Owning framework-specific runtime bootstrapping.

## Components

- `rpc-api`
- `test-api`
- `proto`

## Evolution

### Active

- Stabilize documented API-contract ownership and traceability. Status: active

### Deferred / Obsolete

- Split protocol artifacts into a separate module. Status: deferred - no current repository pressure requires it.
