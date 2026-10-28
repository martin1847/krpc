# BIND-HOST — Configurable Listen Address (Implementation Findings)

Owner: omp. Branch `feat/bind-host` (from origin/dev @ 5e5b120). Reviewer: codex (light).
Date: 2026-07-17. Commits LOCAL, no push.

Motivation: the public demo (demo.krpc.tech) fronts everything with nginx on the same host; the
maintainer wants the krpc process bound to `127.0.0.1` (defence without a firewall). Before this
change both faces bound the wildcard address (`RpcServerBuilder` → `ServerBuilder.forPort(port)`;
`HttpServer` → `bind(port)`), reachable on every interface.

## What changed

New listen-address config `KRPC_BIND_HOST` (env) / `rpc.server.bindHost` (system property). Unset or
blank → the **exact previous wildcard bind** (byte-for-byte, NS-6 default-off). Set → both self-owned
faces bind that address.

| Area | File | Change |
| --- | --- | --- |
| Shared resolver | `rpc-common/.../util/EnvUtils.java` | New `bindHost()` + `BIND_HOST_PROP`/`BIND_HOST_ENV` constants. System property `rpc.server.bindHost` wins over env `KRPC_BIND_HOST`; unset/blank → `null` (= wildcard). |
| gRPC face | `rpc-server/.../RpcServerBuilder.java` (`init`) | `bindHost == null ? ServerBuilder.forPort(port) : NettyServerBuilder.forAddress(new InetSocketAddress(host, port))`. Everything else about the builder identical (executor, OTEL interceptor, D2 `maxConcurrentCallsPerConnection` cast all unchanged). |
| krpc-http face | `http-server/.../HttpServer.java` (`start`) | `bindHost == null ? b.bind(port) : b.bind(new InetSocketAddress(host, port))`. Retains the bound `Channel` (`serverChannel`) so the actual listen address is observable. |
| Test (gRPC) | `rpc-server/src/test/.../RpcServerBindHostTest.java` (new) | set → loopback bind + real loopback call; unset → wildcard. |
| Test (http) | `http-server/src/test/.../HttpServerBindHostTest.java` (new) | set → loopback bind + real loopback TCP connect; unset → wildcard. |
| Docs | this file | — |

## Config design decision (which house style, and why)

There are two config precedents in the repo:

- **`KRPC_OTEL` (ADR-0006 / `KrpcOtel`)**: `System.getProperty("rpc.otel.enabled")` wins over
  `System.getenv("KRPC_OTEL")`, resolved in the **netty-core layer** (rpc-common), works for
  plain-netty + Quarkus + native without a Quarkus container.
- **`KRPC_MCP` (ADR-0004)**: `@ConfigProperty(name="rpc.server.mcp.enabled")` + `EnvUtils.env("KRPC_MCP")`
  fallback, resolved in the **Quarkus layer** (`McpHandler`/`McpGetHandler`).

**Chose the `KRPC_OTEL` (system-property + `KRPC_` env, rpc-common resolver) pattern.** Reason: both
bind sites (`RpcServerBuilder`, `HttpServer`) live in the netty-core layer, below any Quarkus config —
identical to why `KrpcOtel` resolves the flag with `System.getProperty`/`System.getenv` rather than
`@ConfigProperty`. A single `EnvUtils.bindHost()` gives one source of truth for both faces (and
plain-netty callers / tests) with no builder-constructor plumbing and no dual-path precedence
ambiguity. Env `KRPC_BIND_HOST` aligns with the existing `KRPC_*` family (`KRPC_MCP`, `KRPC_OTEL`,
`KRPC_IOURING`). The `rpc.server.bindHost` name is exposed as a JVM system property
(`-Drpc.server.bindHost=127.0.0.1`), exactly as `rpc.otel.enabled` is. For the demo deploy the
natural knob is `KRPC_BIND_HOST=127.0.0.1` in the container env.

Precedence (mirrors `KrpcOtel.resolveEnabled`): system property wins over env; both blank → unset.

## gRPC builder equivalence (premise VERIFY)

- `ServerBuilder.forPort(port)` resolves the Netty provider at runtime
  (`NettyServerProvider` → `NettyServerBuilder.forPort(port)`, which is
  `forAddress(new InetSocketAddress(port))`). So `NettyServerBuilder.forAddress(new
  InetSocketAddress(host, port))` is the **same builder** with a specific host — no other builder
  default changes. VERIFIED: the D2 `instanceof NettyServerBuilder` cast (line ~161) still matches,
  the OTEL interceptor registration is untouched, tests green both paths.
- `grpc-netty` is `compileOnly` in `rpc-server` (already required for the D2 cast); consumers pull it
  at runtime (`rpc-server-quarkus`/`-spring` `runtimeOnly`). The `forAddress` static is only reached
  when `bindHost` is set, i.e. on a running server that already has grpc-netty present. No new
  dependency scope.

## Verified (actually run this session)

Via `gradle` (9.6.0) on Oracle GraalVM 25 (JVM tests).

- **New gRPC test** `RpcServerBindHostTest` — **2 tests, 0 failures**:
  - `bindHostSet_bindsAndServesOnLoopback`: `-Drpc.server.bindHost=127.0.0.1`, real
    `RpcServerBuilder.Builder(...).build().startServer()`. `server.getListenSockets()` → exactly one
    socket at `127.0.0.1` with `isAnyLocalAddress()==false`; a real `RpcClientFactory` call over
    `127.0.0.1` returns `ok` with the echoed value.
  - `bindHostUnset_keepsWildcardBind`: property cleared → `getListenSockets().get(0)` is
    `isAnyLocalAddress()==true` (wildcard preserved).
- **New http test** `HttpServerBindHostTest` — **2 tests, 0 failures**:
  - `bindHostSet_bindsLoopbackAndAcceptsConnection`: real `HttpServer.start()` on a free port;
    retained `serverChannel.localAddress()` is `127.0.0.1` (`isAnyLocalAddress()==false`); a raw
    loopback `Socket.connect(127.0.0.1:port)` succeeds.
  - `bindHostUnset_keepsWildcardBind`: property cleared → bound address `isAnyLocalAddress()==true`.
  - Honest note: rather than assert "not reachable on other interfaces" (flaky in a sandbox), both
    tests assert the **actual bound address on the started server object** plus a positive loopback
    connect — deterministic and dependency-free.
- **No regression** (unset = current behaviour): full suites green, no existing test changed/skipped:
  `gradle :rpc-server:test :http-server:test :rpc-common:test` → **BUILD SUCCESSFUL**.
- **Arch gate (ARCH-001)**: `gradle :arch-test:test` → **BUILD SUCCESSFUL** (the new
  `HttpServer` → `tech.krpc.util.EnvUtils` reference does not trip the freeze-ratchet baseline).
- **Red-first sanity**: the set/unset assertions are complementary — the `isAnyLocalAddress()`
  assertion in the unset test would fail if `bindHost()` ever defaulted to a concrete host, and the
  loopback-address assertion in the set test would fail if the host were ignored.

## NS-7 (native)

Main sources changed (`EnvUtils`, `RpcServerBuilder`, `HttpServer`) → a native rebuild is **not** done
in this round. Local **JVM** verification (above) suffices here; the orchestrator rebuilds the native
image for deploy anyway. No new reflection/JNI/resource surface is introduced:
`NettyServerBuilder.forAddress` and `ServerBootstrap.bind(SocketAddress)` are standard grpc-netty /
netty methods already reachable on the image path (the D2 `NettyServerBuilder` cast already forces the
class in); `InetSocketAddress` is JDK. Expected native-clean, unverified locally — stated, not faked.

## FOR / NOT FOR boundaries

No module boundary crossed into NOT FOR. `rpc-common` gains one env-resolution helper (its FOR:
shared runtime utilities); `rpc-server`/`http-server` change only their own face's bind call. No new
config surface beyond the bind address, no refactor, no service-discovery/LB logic (ADR-0001 intact).

## Remaining risks

- **Hostname vs IP**: `bindHost()` returns the raw string; `new InetSocketAddress(host, port)` /
  `ServerBootstrap.bind` resolve it. A bad host (unresolvable) fails fast at bind time with the
  standard bind exception (http face already shuts the event-loop groups down on bind failure,
  AUD-omp-52). This matches how a bad `port` already behaves; no new swallow path.
- **IPv6/dual-stack**: binding `127.0.0.1` intentionally excludes `::1`; a deployment wanting both
  loopbacks would leave it unset (wildcard) behind nginx, or set the interface it needs. Out of scope
  for this goal (single-address bind).
