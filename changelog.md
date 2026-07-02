
# Unreleased (target 1.0.4 / 1.1.0)

P0 fix package (AGENT-001, ADR-0004) — makes the already-shipped agent surface actually work in a default consumer; these are bug fixes, not flag-gated behaviour changes:

* **Agent HTTP endpoints reachable in a default Quarkus consumer.** `AgentDiscoverHandler` / `AgentInvokeHandler` are discovered reflectively by `HttpHandlerExpose` (`getBeans(Object, @Any)`), so Arc's default `remove-unused-beans=all` stripped them and `/agent/discover` + `/agent/invoke` were absent (HTTP server logged `Skip HTTP Server , no Handlers found.`). Both handlers now carry `@io.quarkus.arc.Unremovable`; the endpoints work with zero consumer action. Container-level `@QuarkusTest` added (the prior unit tests instantiated handlers with `new`, bypassing the container, and missed this).
* **Native reflection metadata for the agent invoke path.** `AgentInvokeRequest` registered in `rpc-server-quarkus` reflection-config so `POST /agent/invoke` deserializes in native mode (the discover `ApiMeta` closure was already covered).
* **`AgentInvokeHandler` javadoc corrected**: not-found/forbidden surface as JSON `code:5` (gRPC `NOT_FOUND`), not HTTP `404` (the netty transport only emits 200/404/500 at the status line; errors ride the JSON `code`).
* **Server concurrent-call cap (CVE-2026-47244 app-layer defence-in-depth, D2).** The Netty gRPC server now sets `maxConcurrentCallsPerConnection`, new config `rpc.server.maxConcurrentCallsPerConnection` (**default 2000**, `0` = unlimited = pre-1.0.4 behaviour). Advertised as HTTP/2 `SETTINGS_MAX_CONCURRENT_STREAMS`, so a high-concurrency single-channel client is **back-pressure queued** (excess streams wait client-side), not failed. Complements the 1.0.3 Netty 4.1.135 bump (transport-layer fix) with an app-layer bound. SPEC §12.1. Integration test asserts the over-cap call queues (not rejected) and in-flight concurrency stays ≤ cap.

P1 MCP bridge (AGENT-001, ADR-0004 P1 re-scoped to a thin bridge) — flag-gated, default OFF:

* **MCP Streamable HTTP bridge (`POST /mcp`).** Hand-written Model Context Protocol endpoint (spec `2025-06-18`, JSON-RPC 2.0) on the existing `http-server` netty host, same process as `/agent/*` — no third-party MCP SDK, no new module or Central artifact. `tools/list` is generated from the live `ApiMeta` (`inputSchema`/`outputSchema` from the DTO type tree + jakarta constraints + `@Doc`, `RpcResult<T>` unwrapped); `tools/call` dispatches through the same `WebMethodRegistry.invokeWeb` path as `/agent/invoke` (credential **not** bypassed). Methods: `initialize`, `notifications/initialized` (202), `tools/list`, `tools/call`, `ping`; JSON-response mode (no SSE); `GET /mcp` → 405, unsupported `MCP-Protocol-Version` → 400. Gated by `rpc.server.mcp.enabled` (env `KRPC_MCP`), **default OFF = byte-level zero new surface**. Verified with real MCP clients — the reference `@modelcontextprotocol/sdk` (raw `initialize` result) and the official `@modelcontextprotocol/inspector` CLI — running `initialize` + `tools/list` + `tools/call`, on **JVM and GraalVM native** (Mandrel 25/JDK25); `initialize`+`tools/list` byte-identical across both, `tools/call` differs only in the runtime timestamp; OFF-path regression (`/mcp` absent) intact. Verbatim transcripts: `docs/mcp-transcripts/jvm.txt` + `docs/mcp-transcripts/native.txt`; contract in SPEC §12.2.
* **`@UnsafeWeb(agentTool=true)` opt-in** (default `false`): MCP tools are a strict subset of `@UnsafeWeb` — the `/agent/discover` web view is unchanged, the two surfaces are distinct. ON with no agentTool method = empty tools list.
* **ADR-0004 revised** (status stays accepted): P1 re-scoped from a standalone runtime MCP module to a thin bridge on P0; records the SDK evaluation (official `io.modelcontextprotocol.sdk:mcp` is Reactor + servlet/spring, not embeddable in krpc's native-zero-glue model → hand-written) and the JSON-only/no-SSE transport decision.


# 1.0.3, 2026-07-02

* **grpc aligned to the Quarkus 3.33 LTS BOM: `io.grpc` 1.82.0 -> 1.79.0** (NATIVE-001 Option A). Kills the consumer-side `resolutionStrategy` force previously required for Quarkus native builds; wire behavior unchanged. SPEC §13.1 support matrix.
* **Netty security wave: 4.1.133 -> 4.1.135.Final.** Closes CVE-2026-47244 + CVE-2026-50560 (HTTP/2 DoS, gRPC hot path) and CVE-2026-50020 (conditional HTTP/1 smuggling). Convergence is build-local (all `io.netty:*` incl. transitive-only `netty-codec-http2`); nothing leaks into published POMs — consumer BOMs stay authoritative. Consumers on Quarkus should adopt BOM 3.33.2.1 (same Netty batch).
* io_uring transport evaluated (flag-gated PoC on `feat/iouring-eval`, NOT shipped): works in native but 5–6% slower than NIO on the typical small-message unary path; deferred to Quarkus 4 / Netty 4.2 (NATIVE-003). SPEC §13 note.
* Docs: SPEC §13 rewritten as the native-image consumer SoT (version matrix, server-provider + substitution workarounds pending ext-rpc 1.0.2, build recipe, checklist).
* Heterogeneously reviewed (codex r1 REQUEST-CHANGES -> fixes -> r2 APPROVE, 0 findings).

# 1.0.2, 2026-06-22

* Per-request server context migrated from a hand-rolled `ThreadLocal` to gRPC-native **`io.grpc.Context`** (`ServerContext` `SC_KEY`; attach/detach in `UnaryMethod`). Behavior-equivalent, no wire change; gRPC-managed scope, virtual-thread-friendly. Heterogeneously reviewed (codex).
* Virtual-thread cleanup: dropped Netty `FastThreadLocal` (`ServerContext`, `ClientContext`); `Es256Signature` now creates a `Signature` per call instead of a per-thread cache.
* **Agent-friendly P0** (ADR-0004): opt-in HTTP `/agent/discover` (web-only `ApiMeta`) + `/agent/invoke` endpoints; hidden services double-filtered, credential not bypassed. Auth/rate-limit are the gateway's responsibility.
* `extRpcVersion` -> 1.0.1 (depends on the `@ConfigMapping` / Quarkus 3.33-compatible ext libraries now on Central).
* Docs: SPEC JWT/JWKS auth + native-image reflection sections; ADR-0004.

# 1.0.1, 2026-06-20

* Trace propagation migrated from B3 multi-header to **W3C Trace Context** (`traceparent`), opaquely forwarded; `tracestate` + `x-request-id` carried; B3 (`x-b3-*`) no longer emitted or read (ADR-0003). Wire change vs 1.0.0 — sibling clients must adopt W3C for cross-service trace continuity.
* gRPC/Netty server executor runs on virtual threads (one named virtual thread per RPC; JDK 21, ADR-0002).
* Build: upgrade to Gradle 9.6.0 (wrapper checksum-pinned); jandex 2.0.0 -> 2.3.0; drop sonarqube plugin; migrate `gradle/upload.gradle` off the removed `Project.exec()` to `providers.exec`.

# 1.0.0 (Maven Central GA), 2026-06-20

* First general-availability release on Maven Central (group `tech.krpc`), promoted from `1.0.0.rc1`.
* Build toolchain: pin and track the official Gradle wrapper 8.14.5 (reproducible, checksum-pinned).
* Quarkus 3.15.2 -> 3.33.2 LTS (Gradle 8.14.5 / Gradle 9 compatible plugin line).
* grpc-java 1.74.0 -> 1.82.0; Netty unified to 4.1.133.Final across the whole runtime graph.
* Native: test-server native build on Mandrel 25 / JDK 25 (container build), language level 21.

# 1.0.2 2025-12-09

* 客户端注入bean使用全量命名
* quarkus/DTO自动反射到8层
* 支持`springboot` JIT模式发布服务


# 1.0.0 , 2023-05-05

* `javax.` -> `jakarta.`
* quarkus -> 3.0
* grpc -> 1.54.1

# 1.0.0 , 2020-12-02

* add client final message support

2020-07-21 GLS , publish online.

# 1.0.0 , 2020-06-30

* .net2.0 client publish

# 1.0.0 , 2020-06-20

* Dictionary key keep same , not camel



# 1.0.0 , 2020-05-06

* remove  Google.Api.CommonProtos
* shortter Property name of Outmessage


# 1.0.0 , 2020-04-24

* ci/cd ok



# 1.0.0-rc , 2020-04-20

* appsettings.json for Client Side

# 1.0.0-rc , 2020-04-15

* Deadline Set Support
* EnableRestCall in appsettings.json

# 1.0.0-rc , 2020-04-14

* ValueType Ok
* Can offer a Simple Rest Wrap for  Grpc

# 1.0.0-rc , 2020-04-07

* Headers of Context is ok
* Contract 1.0.0 is release

# 1.0.0-rc , 2020-04-01

* Plugin System is OK.
* PublishSingleFile --self-contained=false will small Mvc 4Mb/88Mb /  csproj 
* Plugin System with  AssemblyLoadContext 
  * It's recommended that shared dependencies should be loaded into AssemblyLoadContext.Default. This sharing is the common design pattern.
  * AssemblyCatalog ;var files = Directory.EnumerateFiles("DIR", "*.dll", SearchOption.TopDirectoryOnly);
  *   var assembiles = Directory.GetFiles(AppContext.BaseDirectory, "*.dll", SearchOption.TopDirectoryOnly)
            .Select(AssemblyLoadContext.Default.LoadFromAssemblyPath);
  * https://github.com/natemcmaster/DotNetCorePlugins
  * https://codetherapist.com/blog/netcore3-plugin-system/
  * https://medium.com/@mailbox.viksharma/resolve-dependencies-using-mef-and-built-in-ioc-container-of-asp-net-core-aae198cd38b6
  * https://cjansson.se/blog/post/creating-isolated-plugins-dotnetcore
  * DI/Log/Config https://github.com/ibebbs/Cogenity.Extensions
  * https://github.com/thinkabouthub/NugetyCore/wiki/Module-Discovery
  * Learn  Scan From  https://github.com/khellang/Scrutor
  * Learm From .net core 3.0  https://github.com/dapplo/Dapplo.Microsoft.Extensions.Hosting
  * https://github.com/thinkabouthub/NugetyCore
  * https://docs.microsoft.com/en-us/dotnet/core/dependency-loading/understanding-assemblyloadcontext
  * https://docs.microsoft.com/en-us/dotnet/core/tutorials/creating-app-with-plugin-support
  * https://github.com/grpc-ecosystem/grpc-gateway

// TODO


Method : cacheKey , Timeout
Helm Chart

* W3C Tracing  https://gist.github.com/lmolkova/6cd1f61f70dd45c0c61255039695cce8
* API Cache. Throw HashCode
* Simple .NET logging with fully-structured events https://serilog.net
* use message-pack to deir  https://github.com/neuecc/MessagePack-CSharp
* Support stream Call, Like https://github.com/Cysharp/MagicOnion 
* Integrations  yager 
  * https://github.com/Cysharp/MagicOnion#telemetry
  * https://github.com/open-telemetry/opentelemetry-dotnet#auto-collector-implementation-for-activitydiagnosticsource
  * https://github.com/Cysharp/MagicOnion/blob/master/src/MagicOnion.OpenTelemetry/MagicOnionCollector.cs#L306
  * Replace Swagger with 
  * https://github.com/grpc-swagger/grpc-swagger
  * https://github.com/mercari/grpc-http-proxy
* OpenTelemetry exporter, like Prometheus, StackDriver, Zipkin and others.
* GlobalStreamingHubFilters  only Server Side. using StreamingHub


* Hacks such as Domain sharding, resource inlining and image spriting will be counter-productive in an HTTP/2 world.
* HTTP/2 is not a replacement for push technologies such as WebSocket or SSE.
* HTTP/2 Push server can only be processed by browsers, not by applications，Additionally HTTP/2 is not a full duplex protocol so can only respond to requests (though possibly with more than one response thanks to Server Push). You say you only need this for client-server messaging so this may be less of a concern for you. In fact Websockets over HTTP/2 has been approved which will allow the HTTP/2 binary format to be used for websockets by wrapping websockets messages in the HTTP/2 Data frame. 
  
* Combining HTTP/2 and SSE provides efficient HTTP-based bidirectional communication.
* WebSocket will probably remain used but SSE and its EventSource API combined with the power of HTTP/2 will provide the same result in most use cases, just simpler.



* To enumerate all assemblies that the app is composed from, look at Microsoft.Extensions.DependencyModel. E.g. foreach (var l in Microsoft.Extensions.DependencyModel.DependencyContext.Default.RuntimeLibraries) Console.WriteLine(l.Name); will print names of all assemblies that the app is composed from. However, loading all assemblies that the app is composed from tends to scale poorly with size of the application and results in slow startup.



https://github.com/grpc/grpc/blob/master/doc/health-checking.md
 liveness 和 readiness path
  enum ServingStatus {
    UNKNOWN = 0;
    SERVING = 1;
    NOT_SERVING = 2;
  }
service Health {
  rpc Check(string service) returns (ServingStatus status);

  rpc Watch(string service) returns (stream ServingStatus status);
}

