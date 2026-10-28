# GEN-NETTY-102 — ext-rpc-gen missing grpc-netty runtime dep (1.0.1 production regression)

Owner: omp · Worktree: `/Users/martin/Garden/middleware/wt-gen102` · Branch: `fix/gen-netty-dep`
(from origin/dev @ fddc6be) · Commits LOCAL, no push/PR, no version bump.

## Problem

ext-rpc-gen **1.0.1** on a clean consumer classpath throws
`NoClassDefFoundError: io/grpc/netty/NettyServerBuilder` at `Gen.scan` — zero output.

Verified cause chain:
- `Gen.scan` (ext-rpc-gen/.../Gen.java:483/488) calls `RpcServerBuilder.toMeta` / `buildApiMeta`.
  Linking `tech.krpc.server.RpcServerBuilder` requires `io.grpc.netty.NettyServerBuilder`
  (RpcServerBuilder.java:32 `import io.grpc.netty.NettyServerBuilder`).
- rpc-server declares grpc-netty **compileOnly BY DESIGN** (rpc-server/build.gradle:26): server
  consumers receive it transitively via rpc-server-quarkus/-spring `runtimeOnly`. That POM
  omission is intended — **rpc-server was NOT changed.**
- ext-rpc-gen depends on rpc-server directly (bypassing the -quarkus/-spring leaves), and
  `compileOnly` does not propagate to a downstream runtime/POM. So ext-rpc-gen's published POM
  carried no grpc-netty → standalone gen consumers break.
- 1.0.0 did not trip it: its scan path did not load RpcServerBuilder.

## Change

`ext-rpc-gen/build.gradle` — added, with a WHY comment (rpc-server keeps grpc-netty compileOnly;
gen is a leaf that loads RpcServerBuilder at scan time → must carry the runtime dep):

```gradle
implementation "io.grpc:grpc-netty:${grpcVersion}"   // grpcVersion = 1.79.0, root gradle.properties
```

Version is BOM-tracked (root `gradle.properties` `grpcVersion=1.79.0`, ADR NATIVE-001 alignment) —
not guessed.

**Regression guard test** — `ext-rpc-gen/src/test/java/tech/krpc/ext/gen/ScanClasspathSmokeTest.java`
plus fixtures `smokefixture/ISmokeService.java` (`@RpcService @UnsafeWeb`) and `smokefixture/SmokeDto.java`.
It calls the real `Gen.scan("gen-netty-102", "tech.krpc.ext.gen.smokefixture")` — the exact path that
links RpcServerBuilder → NettyServerBuilder — and asserts the `echo` endpoint is discovered.

No version bump (orchestrator owns the 1.0.2 release train). Version stays `1.0.1`.

## What ran

- `gradle :ext-rpc-gen:test` → **BUILD SUCCESSFUL**, 10 tests (9 existing TopoEmitOrderTest + 1 new
  ScanClasspathSmokeTest), all green. Confirmed via `build/test-results/test/*.xml`
  (`ScanClasspathSmokeTest tests="1"`, `TopoEmitOrderTest tests="9"`).
- **Negative control** — temporarily removed the new `implementation grpc-netty` line and re-ran
  `:ext-rpc-gen:test --tests ScanClasspathSmokeTest` →
  `java.lang.NoClassDefFoundError: io/grpc/netty/NettyServerBuilder`, BUILD FAILED. This reproduces
  the exact production error and proves the test is a live guard (not a no-op). Line restored.
- `gradle :ext-rpc-gen:build -x test` → **BUILD SUCCESSFUL**.
- **Published-POM evidence** — `gradle :ext-rpc-gen:generatePomFileForMavenJavaPublication`, then
  grepped `ext-rpc-gen/build/publications/mavenJava/pom-default.xml`:

  ```xml
  <dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty</artifactId>
    <version>1.79.0</version>
    <scope>runtime</scope>
  </dependency>
  ```

  grpc-netty is present in the consumer POM view with **scope=runtime, version=1.79.0**.

## Coverage level achieved / NOT covered

Achieved **more than the stated minimum**: the JUnit test is a genuine guard even at the in-repo
level, because rpc-server's grpc-netty is `compileOnly` and does **not** reach ext-rpc-gen's test
classpath transitively — the negative control proves the test fails without the new `implementation`
dep. Separately, the generated-POM grep proves the consumer-facing POM view now carries grpc-netty
(scope runtime, 1.79.0).

NOT covered:
- No true external-consumer reproduction (no fresh Maven/Gradle project resolving the published
  1.0.2 artifact from a repository and running gen). The in-repo test classpath and the POM grep
  together stand in for it, but they are not a byte-identical consumer resolution.
- The test asserts the scan path links + produces meta; it does not exercise every gen output
  template (those remain covered by TopoEmitOrderTest).

## Source-of-truth / boundaries

- rpc-server FOR/NOT-FOR untouched; its intentional `compileOnly` design (D2, 2026-07-03) preserved.
- No ADR/roadmap/module-evolution status change required: this restores intended runtime behavior
  for a leaf consumer, consistent with the existing compileOnly-in-server design.
- Scope respected: only `ext-rpc-gen` (build.gradle + new test sources) changed.

## Commit

`fix(ext-rpc-gen): declare grpc-netty runtime dep — scan loads RpcServerBuilder (LH 1.0.1 regression)`
(local only; no push, no PR, no version bump.)
