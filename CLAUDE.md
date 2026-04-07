# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KRPC is a RPC framework built on gRPC/HTTP2 with a focus on cloud-native simplicity. Key differentiators:
- No proto files required - services defined as Java interfaces
- Cloud-native: delegates service discovery (k8s), load balancing (istio), telemetry (istio) to infrastructure
- Supports multiple languages (Java, Rust, C#, Dart, TypeScript, Python, Go)

## Build & Test Commands

```bash
# use /opt/gradle/gradle/bin/gradle gradle to build
# Build all modules
gradle build

# Run tests
gradle test

# Run single test
gradle :http-server:test --tests "tech.krpc.http.server.GrpcWebCodecTest"

# Clean build
gradle clean

# Dependency report
gradle allDeps > dep.txt
```

## Architecture

### Module Structure

```
rpc-api/          - Annotations (@RpcService), DTOs (RpcResult, PagedList)
rpc-common/       - FilterChain, serialization, context
rpc-server/       - gRPC server implementation (UnaryMethod, ServerContext)
rpc-server-quarkus/ - Quarkus AOT native image support
rpc-server-spring/  - Spring Boot integration
rpc-client/       - gRPC client stub
rpc-client-spring/  - Spring client integration
http-server/      - HTTP gateway (REST/JSON + gRPC-Web)
```

### Key Components

**Service Definition** - Java interfaces annotated with `@RpcService`:
```java
@RpcService
public interface DemoService {
    RpcResult<HelloResult> hello(HelloReq req);
}
```

**Request/Response Flow**:
1. `InputProto` / `OutputProto` - Internal protobuf messages wrapping serialized data
2. `FilterChain<ServerResult, ServerContext>` - Request/response processing pipeline
3. `ServerContext` - Thread-local context with headers, credentials, MDC

**Serialization**: Uses `Serial.Instance` to handle JSON/protobuf. Input data is either UTF8 (JSON string) or BS (raw bytes).

**Error Handling**: Uses `RpcResult` error codes (not exceptions) for business errors. Exceptions are for system errors (mapped to gRPC Status.UNKNOWN).

### Netty HTTP Server

The `http-server` module provides HTTP gateway functionality:
- `PostHandler` / `GetHandler` - REST/JSON endpoints
- `GrpcWebHandler` - gRPC-Web support (imperative mode, unary only)
- Pipeline: HttpRequestDecoder → HttpResponseEncoder → HttpObjectAggregator → Handler

## Key Patterns

**Thread-Local Context**:
```java
ServerContext.LOCAL.set(ctx);
// ... in filter or service method
ServerContext ctx = ServerContext.current();
```

**Filter Chain** (similar to servlet filters):
```java
FilterChain<ServerResult, ServerContext> chain = new FilterInvokeHelper<>(globalFilters, localFilters).buildFilterChain();
ServerResult result = chain.invoke(ctx);
```

**Service Implementation**:
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

## Important Notes

- Minimum JDK: 17
- use /opt/gradle/gradle/bin/gradle gradle to build
