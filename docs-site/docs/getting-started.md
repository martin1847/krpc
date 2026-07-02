---
sidebar_position: 1
title: Getting Started
---

# Getting Started

KRPC is **interface-first**: you write a Java interface plus DTOs, and the
framework handles transport (gRPC/HTTP2), JSON serialization, validation,
metadata, and client generation. Service authors write **no proto files**.

## Requirements

- JDK 21
- Gradle

## 5-Minute Quickstart

The fastest way to see KRPC run is the
[`examples/quickstart`](https://github.com/martin1847/krpc/tree/dev/examples/quickstart)
module — a single service with **no database and no JWT**.

### 1. Run

```bash
gradle :examples:quickstart:run
```

This starts the server in Quarkus dev mode on port `50051` (Ctrl-C to stop).

### 2. Call

[`rpcurl`](https://github.com/martin1847/krpc-crates/) is the command-line KRPC
client. In another terminal:

```bash
rpcurl http://127.0.0.1:50051/quickstart/Hello/hello -d '{"name":"krpc"}'
```

Expected response:

```json
{"code":0,"data":{"message":"Hello, krpc!","timestamp":1751000000000}}
```

## What the quickstart contains

```java
@UnsafeWeb
@RpcService(description = "KRPC quickstart demo service")
public interface HelloService {
    RpcResult<HelloReply> hello(HelloRequest req);
}
```

- `@RpcService` on the **interface** — the name derives to `Hello`, so the call
  path is `quickstart/Hello/hello`.
- `@UnsafeWeb` marks it reachable directly over the HTTP gateway (so `rpcurl` /
  browsers can call it). Internal services omit it.
- The implementation is a plain `@ApplicationScoped @Startup` bean.
- DTOs use boxed scalar fields; `jakarta.validation` constraints (e.g.
  `@NotBlank`) are enforced automatically.

## The one rule that bites first

Every RPC method must return `RpcResult<Dto>` and take **at most one parameter**:

```java
RpcResult<SomeDto> methodName(OneDto req)   // exactly one param
RpcResult<SomeDto> methodName()             // or zero params
```

A method violating either is silently dropped. Merge multiple inputs into one
DTO.

## Next steps

- The full authoring handbook (method contract, error model, DTO rules, auth,
  native image) is in
  [SPEC.md](https://github.com/martin1847/krpc/blob/dev/SPEC.md).
- See [Reference](./reference.md) for all the canonical documents.
