# KRPC

[简体中文](README.zh-CN.md)

## What is KRPC

KRPC is a contract-first, agent-native RPC framework for the JVM (JDK 21): you write **one Java interface plus DTOs** — no hand-written `.proto` — and the framework turns that single contract into a gRPC/HTTP2 service, a JSON HTTP API, typed clients for TypeScript / Dart / Python, and an opt-in [MCP](https://modelcontextprotocol.io) tool surface that AI agents call directly.

The Java interface is the source of truth. KRPC handles transport, JSON serialization, `jakarta` validation, runtime metadata, and client generation, so service authors write business logic — not schemas, stubs, or boilerplate.

KRPC leans on the platform instead of rebuilding it: service discovery, load balancing, telemetry, ingress TLS, and mesh policy stay with Kubernetes, Istio, gateway, or deployment infrastructure ([ADR-0001](docs/decisions/ADR-0001-repository-scope.md)). It is used in production in e-commerce, education, and local-service products.

## Why the agent era needs it

In the agent era, services are no longer called only by other services — **agents call them as tools**. A KRPC Java interface is simultaneously the RPC contract *and*, when you opt in, the MCP tool surface an AI agent invokes. One annotated interface serves a browser, a service-to-service caller, a generated TS/Dart client, and an LLM agent — from a single definition, with no separate tool-wrapping layer.

The mechanism is KRPC's runtime self-description ([ADR-0004](docs/decisions/ADR-0004-agent-friendly-introspection.md)). Every server exposes `ApiMeta` — service and method signatures, full DTO type trees, `@Doc` documentation, and validation constraints — which powers three agent-facing HTTP surfaces:

- **`GET /agent/discover`** — the web-visible `ApiMeta` as JSON, so an agent can introspect a live service ([agent guide](docs/agent-guide.md)).
- **`POST /agent/invoke`** — resolve `Service/method`, forward JSON, and dispatch through the *same* credential and filter path as a gRPC call.
- **`POST /mcp`** — a hand-written [Model Context Protocol](https://modelcontextprotocol.io) bridge (spec `2025-06-18`, JSON-RPC 2.0 over Streamable HTTP; no third-party SDK), whose `tools/list` / `tools/call` are generated from the same `ApiMeta` ([SPEC §12.2](SPEC.md)).

Exposure is layered and **default-safe**. A service is internal (hidden) unless annotated `@UnsafeWeb`; only `@UnsafeWeb(agentTool=true)` — or method-level `@UnsafeWeb.AgentTool` — opts a method into the MCP tool set, and `@UnsafeWeb` alone never creates a tool. The tool attribute defaults to `false`, and the MCP bridge itself is **off by default** (`rpc.server.mcp.enabled=false`, env `KRPC_MCP`), so a disabled deployment ships zero new wire surface. Credentials are never bypassed on the agent path.

> **AI agent?** Install the [krpc skill](skills/krpc/SKILL.md) and read the [agent guide](docs/agent-guide.md) to discover and call KRPC services over HTTP.

## How it differs from protobuf-gRPC

- **No hand-written `.proto`.** The Java interface and its DTOs *are* the contract (enforced at method discovery in `RefUtils`); there is no separate IDL to keep in sync.
- **JSON by default.** The transport is gRPC/HTTP2, but the default codec is JSON for broad client reach. On the wire, payloads ride a fixed `InputProto`/`OutputProto` envelope (JSON or bytes inside) rather than a per-message protobuf schema ([SPEC §14](SPEC.md)).
- **HTTP + gRPC dual face.** A gRPC gateway (`rpc.server.port`, default `50051`) and a plain-HTTP server (`http.port`, default `8080`, serving `/agent/*` and `/mcp`) run in the same process.
- **GraalVM native, JDK 21, virtual threads.** First-class native-image support via the Quarkus integration ([SPEC §13](SPEC.md)); blocking handlers run on virtual threads ([ADR-0002](docs/decisions/ADR-0002-jdk21-virtual-threads.md)).
- **gRPC interop, honestly.** Because the wire uses KRPC's generic envelope, a stock protobuf-generated gRPC stub does **not** interoperate directly — you call via a KRPC-generated client, `rpcurl`, the HTTP `/agent` face, or MCP. The gRPC path has no runtime schema handshake (`RPCURL-001`); introspect over `GET /agent/discover` instead.

## FAQ

**Is KRPC compatible with existing gRPC / protobuf clients?**
Not directly. KRPC uses gRPC/HTTP2 as transport but carries a fixed `InputProto`/`OutputProto` envelope with JSON (or bytes) inside, not per-service protobuf messages — so a stub generated from a hand-written `.proto` cannot call a KRPC service as-is. Call it through a KRPC-generated client (Java/TS/Dart/Python), `rpcurl`, the HTTP `/agent/invoke` endpoint, or the MCP bridge.

**Do I need `.proto` files?**
No. For normal business APIs you write a Java interface returning `RpcResult<Dto>` plus DTOs; that interface is the contract and the source for client generation. KRPC uses protobuf only as an internal wire envelope, never as an author-facing IDL.

**How do agents / MCP clients call a KRPC service?**
Enable the MCP bridge (`KRPC_MCP=true`) and mark the methods you want to expose with `@UnsafeWeb(agentTool=true)` (or `@UnsafeWeb.AgentTool` per method); an MCP client then does `tools/list` + `tools/call` over `POST /mcp`. Agents that don't speak MCP can `GET /agent/discover` for the JSON schema and `POST /agent/invoke` to call a method. Both paths run the same credential checks as gRPC.

**What does the generated TypeScript / Dart client give me?**
A typed client derived from the same interface + DTOs, so a frontend or mobile app calls methods with exact request/response types (and `@Doc` docs) — no manual HTTP wiring, no separately maintained schema. Clients also exist for Python, Go/k6, Java, and `rpcurl`.

**Does it run as a GraalVM native image?**
Yes, via the `rpc-server-quarkus` integration (SPEC §13); the quickstart is verified building and booting as a native binary. Mind the documented native-reflection pitfalls (e.g. Caffeine bounded caches) when adding libraries.

**How does it compare to Spring gRPC / protobuf-gRPC?**
KRPC drops the `.proto`/IDL step (interfaces are the contract), defaults to JSON, and adds a JSON HTTP face plus agent/MCP introspection on top of the gRPC transport. Classic protobuf-gRPC gives you schema-first cross-language contracts and direct protobuf interop; KRPC trades that for interface-first ergonomics and agent-native surfaces on the JVM.

**What JDK and framework does it need?**
JDK 21 (virtual threads are a supported runtime feature, not a roadmap item) and Gradle. The runtime is framework-agnostic, with ready integrations for Quarkus (`rpc-server-quarkus`, incl. native) and Spring (`rpc-server-spring` / `rpc-client-spring`).

**How do I expose only some methods as agent tools?**
Exposure is opt-in and layered. Leave a service unannotated to keep it internal; add `@UnsafeWeb` to reach it from browsers/HTTP; add `agentTool=true` at the interface level, or `@UnsafeWeb.AgentTool` on individual methods, to publish exactly those methods as MCP tools. Defaults are all-OFF, and the MCP bridge stays off unless `KRPC_MCP` is set.

## Try It Live

A public sandbox runs the quickstart natively at `https://demo.krpc.tech` — no local
build needed. `HelloService` only, with fake data. nginx is the only entry point.

MCP (JSON-RPC 2.0 over Streamable HTTP) — list the tools, then call one:

```bash
curl -sS https://demo.krpc.tech/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

curl -sS https://demo.krpc.tech/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"Hello_hello","arguments":{"name":"you"}}}'
```

HTTP introspection — the web-only `ApiMeta`:

```bash
curl -sS https://demo.krpc.tech/agent/discover
```

rpcurl (needs `>=1.1.0` for the https discover path):

```bash
rpcurl discover https://demo.krpc.tech
rpcurl https://demo.krpc.tech/quickstart/Hello/hello -d '{"name":"you"}'
```

> Public fake-data sandbox — may reset anytime. Error-envelope demo: call `Hello_hello`
> with `{}` (empty args) to see `{code,message,violations}`, e.g.
> `{"code":3,"message":"Invalid input","violations":[{"field":"name","constraint":"must not be blank"}]}`.

## Modules

- `rpc-api`: annotations and shared API models.
- `rpc-common`: serialization, context, filters, metadata, and utilities.
- `rpc-client`: Java client runtime.
- `rpc-server`: Java server runtime.
- `rpc-client-spring`: Spring client integration.
- `rpc-server-spring`: Spring server integration.
- `rpc-server-quarkus`: Quarkus and native-image integration.
- `http-server`: HTTP gateway support.
- `test-rpc-gen`: client code generation examples.
- `rpcurl`: command-line RPC client.

![Architecture](./ARCHITECTURE.png)

## Requirements

- JDK 21
- Gradle

Latest version: `1.0.3` (see the [support policy](docs/support-policy.md) for the version/support matrix and [SPEC.md](SPEC.md) for the authoring handbook).

```gradle
implementation "tech.krpc:rpc-api:1.0.3"
implementation "tech.krpc:rpc-client:1.0.3"
implementation "tech.krpc:rpc-server:1.0.3"
```

## Define An API

Add `rpc-api` to the API module:

```gradle
plugins {
    id "org.kordamp.gradle.jandex" version "2.0.0"
}

dependencies {
    api "tech.krpc:rpc-api:1.0.3"
}
```

Define services as Java interfaces:

```java
@RpcService
public interface DemoService {
    RpcResult<HelloResult> hello(HelloReq req);
}

public class HelloReq {
    @Doc("name")
    @NotBlank
    private String name;
}
```

API rules:

- Return `RpcResult<DTO>`.
- Use one input object per method.
- Use DTOs for request and response bodies.
- Use `jakarta.validation` for input validation.
- Use `@Doc` for fields that need generated client documentation.
- Avoid `Map` in API contracts unless there is a strong reason.
- Avoid enum fields in response DTOs when long-term client stability matters.

Publish API artifacts with semantic versions. Avoid `SNAPSHOT` for shared API packages.

## Implement A Server

Add the API and server runtime:

```gradle
dependencies {
    implementation project(":your-api")
    implementation "tech.krpc:rpc-server:1.0.3"
}
```

Implement the interface:

```java
@ApplicationScoped
@Startup
public class DemoServiceImpl implements DemoService {
    @Override
    public RpcResult<HelloResult> hello(HelloReq req) {
        return RpcResult.ok(new HelloResult("hello " + req.getName()));
    }
}
```

## Call With rpcurl

`rpcurl` is available from [martin1847/krpc-crates](https://github.com/martin1847/krpc-crates/).

```bash
export KRPC_APP="https://example.com/demo"

rpcurl "$KRPC_APP/Demo/hello" -d '{"name":"krpc"}'
```

Common options:

```text
-d, --data <DATA>      request JSON
-f, --file <FILE>      request JSON file
-t, --token <TOKEN>    Authorization: Bearer token
-c, --cookie <COOKIE>  Cookie header
-H, --header <HEADER>  custom header, e.g. -H a=b
-v, --verbose          verbose output
```

## Error Handling

Use soft errors for business failures:

- Server: return `RpcResult` with a non-OK code and message.
- Client: check `isOk()` before reading data.

Use exceptions for system failures, security failures, validation failures, and unexpected runtime errors.

## Quickstart (5 minutes)

The fastest way to see KRPC run — a single module, no database, no JWT:

```bash
gradle :examples:quickstart:run
```

Then call it:

```bash
rpcurl http://127.0.0.1:50051/quickstart/Hello/hello -d '{"name":"krpc"}'
```

See [`examples/quickstart/`](examples/quickstart/README.md) for the walkthrough.

## Run Existing Demo

The repository includes an integration demo in `test-api` and `test-server`.

Build it:

```bash
gradle :test-server:build -x test
```

Run it:

```bash
gradle :test-server:quarkusDev \
  -Dquarkus.datasource.password=youshallnotpass \
  -Ddebug=false \
  --console=plain
```

Call it:

```bash
rpcurl http://127.0.0.1:50051/test-server/Demo/hello \
  -d '{"name":"krpc","age":18}'
```

This is an integration demo, not a minimal quickstart template. It includes MySQL, MyBatis, and JWKS-related configuration; local JWKS fetch warnings do not block the `Demo/hello` call.

## Clients

Generated or companion clients exist for Dart, TypeScript, Python, Go/k6, Java, and rpcurl.

## Project Governance

- Documentation index: [docs/INDEX.md](docs/INDEX.md)
- Repository scope: [ADR-0001](docs/decisions/ADR-0001-repository-scope.md)
- JDK 21 and virtual threads: [ADR-0002](docs/decisions/ADR-0002-jdk21-virtual-threads.md)
