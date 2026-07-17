# KRPC native image (GraalVM) — full reference

Extracted from `SPEC.md §13` to keep the contract lean. Single source of truth for building
krpc services as native images — humans and agents read the same file. Consumer-proven
2026-06 on 8 downstream Quarkus 3.33.2 services (boot-to-ready 0.028s native). Items tagged
`[gap → NATIVE-00x]` are framework defects with roadmap items: apply the workaround now,
delete it when the item completes. The day-1 checklist lives in `SPEC.md §13`.

### 13.1 io.grpc version alignment — Quarkus LTS support matrix

krpc tracks the Quarkus LTS BOM's io.grpc version (Option A, ADR NATIVE-001).
`gradle.properties` pins `grpcVersion` to what the supported Quarkus LTS ships, so
a Quarkus consumer's highest-wins resolution converges on a single io.grpc — no
consumer-side force, and native-image's `Target_io_grpc_ServiceProviders`
substitution matches the class shape.

| krpc release                    | Quarkus LTS | io.grpc | consumer force |
|---------------------------------|-------------|---------|----------------|
| ≥ 1.0.3 (this alignment)        | 3.33.x LTS  | 1.79.0  | none — aligned |
| published ≤ 1.0.2               | 3.33.x LTS  | 1.82.0  | required (below) |

(Bump `grpcVersion` in lockstep when adopting the next Quarkus LTS.)

**For published krpc ≤1.0.2 only** (ships io.grpc 1.82.0, skewed above the BOM → native-image
aborts during Initializing): force `io.grpc:*` back to the BOM version in the root
build — `configurations.all { resolutionStrategy.eachDependency { if (it.requested.group == 'io.grpc') it.useVersion '1.79.0' } }`.

### 13.2 Server-side native support — use `ext-rpc` ≥ 1.0.2 (NATIVE-002, shipped)

Since `tech.krpc.ext:ext-rpc` **1.0.2**, the Quarkus extension registers everything a
native SERVER needs with zero per-service glue: the deployment processor emits
reflective registration for the grpc provider impls and a build-time GraalVM
`Feature` that bakes `io.grpc.ServerProvider.provider()` into the image heap
(krpc itself only covers the client side —
`rpc-client/.../ext/GraalvmBuild.java:14-18`). It also gates its grpc-netty
substitutions on `quarkus-grpc-common` absence, so they no longer collide with
Quarkus's own.

**On `ext-rpc` ≤ 1.0.1 only**, a native server dies at boot with
`ManagedChannelProvider$ProviderNotFoundException: No functional server found`
and duplicate-substitution aborts; either upgrade, or apply the legacy trio
(per-service `ServerProvider` Feature + `@RegisterForReflection` provider holder +
`quarkus.class-loading.removed-resources` strip) — recipe preserved in the
`v1.0.3` tag of this file and in `ext-rpc` PR #2.

### 13.3 Build recipe and known runtime issues

- Build command: `SPEC §12` (Build, test, release). Native needs
  `-Dquarkus.package.jar.enabled=false` (Gradle can't output both), and the
  builder image must match your Quarkus/JDK line — for the JDK 21 baseline use
  `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21` (krpc's own
  test-server was additionally validated on Mandrel 25 / jdk-25).
- Static-heap violations are per-service: any `static final` SecureRandom /
  Random / network-touching singleton fails analysis; fix with
  `--initialize-at-run-time=<class>` (the native build error names the class).
- Known issue: a native runner **SIGSEGVs at startup when datasource env/config
  is absent** (logs "started", then exit 139). Ensure `QUARKUS_DATASOURCE_*` is
  set; JVM mode fails gracefully, native does not.

### 13.4 io_uring transport (evaluated 2026-07)

Status: evaluated. A flag-gated PoC exists on eval branch `feat/iouring-eval` —
**not shipped; the default transport stays NIO in native / epoll-or-NIO on JVM.**

- **Netty artifact constraint.** Today's stack (Quarkus 3.33 LTS = Netty 4.1) can
  only use the ARCHIVED incubator artifact
  (`io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.26.Final`).
  The graduated transport (`io.netty.channel.uring`) is Netty-4.2-only, which
  arrives with Quarkus 4 / Vert.x 5.
- **Native-image: works.** Flag `KRPC_IOURING`, hand-authored JNI/reflect/resource
  metadata, `--initialize-at-run-time`; +0.56 MiB image, +1.9 MiB RSS.
- **Benchmark verdict (aarch64, containerized): 5–6% SLOWER than NIO** on krpc's
  typical small-message unary path. io_uring's win case (many connections,
  syscall-bound) is not this profile. Details:
  workspace `docs/orchestration/IOURING-001_{RESEARCH,BENCH}_omp.md`.
- **Ops note.** Docker's default seccomp profile blocks io_uring syscalls
  (`io_uring_setup` → EPERM); running the flag ON in containers needs an allowing
  seccomp profile.

### 13.5 Reflection coverage (what the framework registers for you)

Native is closed-world: the framework registers reflection at build time, but
only for what its build-time scan can reach. Know what is and is not covered.

- **The extensions must be on the native build.** DTO reflection is registered by
  the `ext-rpc` Quarkus deployment processor: it indexes every `@RpcService`
  interface (Jandex), walks each method's param + return types, and emits
  `ReflectiveClassBuildItem` for the DTOs with `methods(true).fields(true)`
  (`ext-rpc/ext-rpc-deployment/.../RpcProcessor.java:75-94,148-150`). `ext-mybatis`
  does the equivalent for its mapper/entity types. Drop the extension and **nobody**
  registers your DTOs — they reflect fine on JVM but fail at native runtime.
- **Nested DTOs are auto-covered only 8 levels deep.** After the top-level DTOs, the
  processor recurses through nested field types up to `max_level = 8`
  (`RpcProcessor.java:158-170`); beyond that it logs `TOO DEEP Nest Dto`
  (`:180`) and stops registering. Keep DTO nesting shallow, or register deeper types
  by hand.
- **Third-party bean types are not scanned.** The recursion explicitly skips `java.*`
  types (`RpcProcessor.java:225`) and only follows types reachable from your own
  DTO fields — an external-library class referenced by a DTO is outside the scan and
  will throw at native runtime. Register it yourself with Quarkus's
  `@RegisterForReflection` (`io.quarkus.runtime.annotations.RegisterForReflection`),
  e.g. on an aggregate class:

  ```java
  import io.quarkus.runtime.annotations.RegisterForReflection;

  @RegisterForReflection(targets = { com.vendor.lib.Foo.class, com.vendor.lib.Bar.class })
  public final class NativeReflectionConfig {}
  ```

  Or add the class to a `META-INF/native-image/<group>/reflection-config.json`.
- **Framework classes are already registered — don't re-register them.** krpc's own
  runtime types ship reflection metadata in each module's
  `META-INF/native-image/*/reflection-config.json` + `native-image.properties`
  (`rpc-api/...`, `rpc-common/...`, `rpc-client/...`,
  `rpc-server-quarkus/src/main/resources/META-INF/native-image/rpc-server/...`).
  You only own the third-party types your DTOs pull in.
