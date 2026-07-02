# KRPC

[简体中文](README.zh-CN.md)

KRPC is an interface-first RPC framework for cloud-native services.

Write a Java interface, publish it as the API contract, and let KRPC handle RPC transport, validation, metadata, and client generation. Service authors do not need to write proto files for normal business APIs.

## What It Does

- Uses gRPC / HTTP/2 as the transport.
- Uses JSON by default for broad client reach.
- Treats Java interfaces and DTOs as the API source of truth.
- Generates clients for frontend, mobile, scripting, and service-to-service use.
- Supports JDK 21 and virtual threads.
- Works with Kubernetes and service mesh instead of replacing them.

KRPC does not own service discovery, load balancing, telemetry, ingress TLS, or mesh policy. Those belong to Kubernetes, Istio, gateway, or deployment infrastructure.

KRPC is used in production in e-commerce, education, and local service products. Public adopter names are omitted unless explicit approval is granted.

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
