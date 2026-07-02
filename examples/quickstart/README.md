# KRPC Quickstart (5 minutes)

A single-module KRPC service with **no database and no JWT** — the smallest thing
that clones, runs, and answers an RPC call. (The repository's `test-server` is a
full integration demo with MySQL/MyBatis/JWKS; this is the minimal starter.)

## 1. Run

```bash
gradle :examples:quickstart:run
```

This starts the server in Quarkus dev mode on port `50051` (Ctrl-C to stop).

## 2. Call

[`rpcurl`](https://github.com/martin1847/krpc-crates/) is the command-line KRPC
client. In another terminal:

```bash
rpcurl http://127.0.0.1:50051/quickstart/Hello/hello -d '{"name":"krpc"}'
```

Expected response:

```json
{"code":0,"data":{"message":"Hello, krpc!","timestamp":1751000000000}}
```

## What's here

- `HelloService` — the `@RpcService` interface (`@UnsafeWeb` so rpcurl/browsers
  can reach it over the HTTP gateway). Name derives to `Hello`, so the path is
  `quickstart/Hello/hello`.
- `HelloServiceImpl` — `@ApplicationScoped @Startup` implementation.
- `HelloRequest` / `HelloReply` — DTOs (boxed fields; `@NotBlank` validation).

That's the whole contract: write a Java interface, implement it, call it. See
[SPEC.md](../../SPEC.md) for the full authoring handbook.
