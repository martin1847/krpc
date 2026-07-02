# IMPL findings — KRPC-001 Phase 3: grpc 1.74→1.82 + Netty 4.1.110→4.1.133 (omp)

> Owner: omp. Worktree `/Users/martin/Garden/wt-krpc-gradle9`, branch `feat/gradle9-bump`
> (stacks on Phase 2 `8de7943`). Goal: `…/KRPC-001-P3-grpc-netty_GOAL.md`. Reviewer: codex.
> **Status: DONE — round-1 codex fix applied (Spring Netty downgrade), green, re-committed
> locally, not pushed. Re-review pending.**
> Toolchain: GraalVM JDK 21, Gradle 8.14.5, Quarkus 3.33.2.

## What changed

- `gradle.properties:14` → `grpcVersion=1.74.0` **→ `1.82.0`**.
- `gradle.properties:28` → `nettyGrpcVersion=4.1.110.Final` **→ `4.1.133.Final`**.
- `errorProneVersion` **NOT changed** (stays `2.45.0`). grpc-netty 1.82.0 pulls
  `error_prone_annotations:2.48.0` transitively (annotations-only, resolves on the runtime
  classpath via highest-wins), but the build compiled+assembled green without forcing a bump,
  so per the goal it was left untouched.
- `test-server-spring/build.gradle` → added `ext['netty.version'] = nettyGrpcVersion` (Round-1
  fix B1 — see below). This is grpc/netty-scoped Gradle config only.
- No Quarkus/Gradle/language/native edits; `nativeArgs` untouched (Phase 4). Diff beyond Phase 2
  is `gradle.properties` (2 lines) + `test-server-spring/build.gradle` (1 line) + this findings doc.

## Round 1 fix (codex REQUEST CHANGES — 1 BLOCKING: B1)

### B1 — Spring dependency-management downgraded Netty to 4.1.117 (invariant broken)

My first proof only covered `:test-server` + `:rpc-server-quarkus` and missed the Spring path.
`test-server-spring` applies `org.springframework.boot 3.3.8` + `io.spring.dependency-management
1.1.6`; the Spring Boot BOM pins `netty.version=4.1.117.Final` and **downgraded** grpc-netty
1.82's 4.1.133 on `:test-server-spring:runtimeClasspath`:

```
# BEFORE fix — :test-server-spring:runtimeClasspath
io.netty:netty-codec-http2:4.1.133.Final -> 4.1.117.Final
io.netty:netty-common:4.1.133.Final -> 4.1.117.Final
io.netty:netty-handler-proxy:4.1.133.Final -> 4.1.117.Final
...  (whole family resolved to 4.1.117 < grpc's 4.1.133 → invariant broken)
```

(Only `test-server-spring` was affected — it is the sole module applying
`io.spring.dependency-management`. Standalone `:rpc-server-spring` / `:rpc-client-spring`
runtimeClasspath already resolved 4.1.133 — verified — but all three are re-verified below.)

**Fix:** override the Spring Boot BOM's Netty version via the property it honours —
`ext['netty.version'] = nettyGrpcVersion` (= 4.1.133.Final) in `test-server-spring/build.gradle`.
So `io.spring.dependency-management` now resolves Netty 4.1.133. Gradle-config-only,
grpc/netty-scoped, no source change.

## Why 4.1.133 (Netty skew control)

- grpc-java 1.82.0 pins Netty **4.1.133.Final** (verified from `grpc-netty-1.82.0.pom`:
  `netty-codec-http2`, `netty-handler-proxy`, `netty-transport-native-unix-common` all 4.1.133,
  which transitively brings the whole netty family at 4.1.133).
- Quarkus 3.33 BOM manages Netty **4.1.130** — but `test-server` consumes it via
  `implementation platform(io.quarkus.platform:quarkus-bom)` (NOT `enforcedPlatform`), so the
  BOM's 4.1.130 is a *constraint*, not a hard pin. Gradle conflict resolution picks the highest:
  `max(4.1.130, 4.1.133) = 4.1.133`. Overriding `nettyGrpcVersion` UP to 4.1.133 makes every
  netty artifact we declare 4.1.133 too, so the whole graph converges on a single 4.1.133.
- Invariant "grpc-netty's Netty ≤ app Netty" holds: app Netty = grpc Netty = 4.1.133 (equal).
- grpc 1.82 announced Netty 4.2 for its *next* release — we stay on 4.1.133 (4.2 not BOM-managed,
  out of scope).

## SINGLE NETTY PROOF (required — ALL modules, post-fix)

`./gradlew :<module>:dependencies --configuration runtimeClasspath` for **every** module
(including the three Spring modules).

Per-module RESOLVED `io.netty:*` versions (post-`->` arrow, distinct):
```
ext-rpc-gen        -> 4.1.133.Final
http-server        -> 4.1.133.Final
rpc-client         -> 4.1.133.Final
rpc-client-spring  -> 4.1.133.Final
rpc-common         -> 4.1.133.Final
rpc-server         -> 4.1.133.Final
rpc-server-quarkus -> 4.1.133.Final
rpc-server-spring  -> 4.1.133.Final
test-server        -> 4.1.133.Final
test-server-spring -> 4.1.133.Final   # was 4.1.117 before the B1 fix
```
(`rpc-api`, `test-api`, `test-jwks` carry no Netty on runtimeClasspath.)

Global resolved frequency + bad-version scan across all modules:
```
 268 4.1.133.Final
resolved 4.1.117: 0
resolved 4.1.130: 0
resolved 4.1.110: 0
downgrade arrows (-> 4.1.117/130/110) or netty "(selected by rule)": (none)
```

→ A SINGLE resolved Netty `4.1.133.Final` across EVERY module's runtimeClasspath; zero artifacts
at 4.1.117 / 4.1.130 / 4.1.110; no `(selected by rule)` downgrade remains. The Quarkus-BOM
(4.1.130) and Spring-BOM (4.1.117) version requirements both resolve up to 4.1.133 (Quarkus via
non-enforced platform highest-wins; Spring via the `ext['netty.version']` override). Invariant
`grpc-netty Netty (4.1.133) <= app Netty (4.1.133)` holds everywhere.

## What was verified (actually ran, on Gradle 8.14.5 / GraalVM JDK 21)

1. **`./gradlew clean build -x test`** — `BUILD SUCCESSFUL in 26s`, 89 tasks; compile + assemble
   all modules incl. Quarkus augmentation (`quarkusAppPartsBuild` / `quarkusBuild`) now running on
   grpc 1.82 + Netty 4.1.133. (errorprone 2.48.0 downloaded transitively; build still green
   without a gradle.properties bump.)
2. **DB-free core module tests** — `:rpc-api :rpc-common :rpc-client :rpc-server :http-server :test`
   → `BUILD SUCCESSFUL` (same profile as P1/P2: these modules carry no executable JUnit tests, so
   this confirms compile + test-task wiring on the new grpc/netty, not assertion coverage).
3. **Single Netty proof** — see above (103 × 4.1.133.Final; 0 × 4.1.130/4.1.110).
4. **Runtime smoke against local MySQL (PASS)** — `:test-server:test --tests test.krpc.TestBookService`
   (`@QuarkusTest`) with datasource overrides via env (test workers inherit env; CLI `-D` does not
   reliably reach the forked worker), `RPC_SERVER_PORT=50061`:
   ```
   [t.k.m.r.b.QuarkusDataSourceFactory] (Test worker) : Bind QuarkusDataSource :<default>
   [i.quarkus] test-server 1.0.0.rc1 on JVM (powered by Quarkus 3.33.2) started in 2.494s.
   Installed features: [agroal, cdi, compose, ext-mybatis, ext-rpc, hibernate-validator,
                        jdbc-mysql, narayana-jta, smallrye-context-propagation]
   BUILD SUCCESSFUL in 11s
   ```
   → App boots on grpc 1.82 + Netty 4.1.133; datasource wires; gRPC server binds (50061);
   TestBookService passes (its `BookMapper` is mocked, so no schema access). No grpc/netty wiring
   failure. Port note: 50051 is still held by a separate pre-existing Quarkus 3.15.2 dev process
   (`order-server`) — left running; used 50061.

## What was NOT verified

- Table-backed MyBatis integration tests — `example` DB has no schema/tables; TestBookService
  mocks its mapper, so SQL paths were not exercised. **Schema-load sub-task** (load DDL, then run
  table-backed tests).
- Native image build — out of scope (Phase 4).
- `test-server-spring` `@SpringBootTest` — env/network-gated (Phase 1 finding), unrelated to grpc/netty.

## Residual assumptions / notes

- `errorProneVersion` stays 2.45.0 (direct dep in rpc-common); grpc's transitive 2.48.0 wins on
  runtime classpath via highest-wins. If a future strict-resolution or compile use forces it,
  bump to 2.48.0 — not needed now.
- The single stray `netty-common:4.1.110.Final -> 4.1.133.Final` arrow is a transitive requesting
  4.1.110, harmlessly resolved up to 4.1.133 (part of the convergence proof).

## Acceptance checklist (against the goal)

- [x] `./gradlew clean build -x test` green incl. Quarkus augmentation on grpc 1.82 + Netty 4.1.133.
- [x] DB-free core module whitelist tests green.
- [x] Single Netty proof (ALL modules, runtimeClasspath): ONE resolved Netty `4.1.133.Final`
  (268 entries); 4.1.117/4.1.130/4.1.110 = 0; no `(selected by rule)` downgrade. Lines pasted above.
- [x] Runtime smoke: test-server boots on grpc 1.82 + Netty 4.1.133; datasource + gRPC wire (PASS).
- [x] `git diff 8de7943..HEAD` touches ONLY `gradle.properties` (grpc + netty; errorProne NOT
  forced) + `test-server-spring/build.gradle` (netty.version override) + this findings doc.
  No Quarkus/Gradle/language/native/source edits.
- [x] No test deleted/skipped/weakened.

## Next

- Schema-load sub-task for table-backed MyBatis tests (env-gated).
- Phase 4 (native): includes the deferred `nativeArgs` `-Ob` → `quarkus.native.additional-build-args`
  migration.
- Awaiting codex re-review (stacked on Phase 2 `8de7943`, local, not pushed).
