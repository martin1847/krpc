# CLIENTDEP-ALIGN — rpc-server-quarkus direct rpc-client dependency

Owner: omp · Branch `fix/client-dep-align` (from krpc origin/dev, post-#31) · Reviewer: codex (light)
Scope: build/dependency declarations + verification only. Zero production source changes.

## Problem (OTEL-003 root-cause class)

`rpc-server-quarkus` reached `rpc-client` **only transitively** via the external artifact
`tech.krpc.ext:ext-rpc:1.0.3`, whose POM pins `tech.krpc:rpc-client:1.0.3` (runtime):

```
ext-rpc-1.0.3.pom → <dependency> tech.krpc:rpc-client:1.0.3 (runtime)
```

Because `rpc-server-quarkus` declared no direct `rpc-client` dependency, a downstream consumer
of `rpc-server-quarkus:X` that also used `rpc-client` silently resolved `rpc-client:1.0.3` (the
old pin) — client and server drift apart. This is the structural class behind OTEL-003.

## Change

| Where | What |
| --- | --- |
| `rpc-server-quarkus/build.gradle` | Added `api project(':rpc-client')` (with a source-reference comment). Placed beside the existing `api project(':rpc-server')` / `api project(':http-server')`. |

**`api`, not `implementation`** — a direct declaration at the project version wins in both
resolution engines regardless of scope: **Maven** mediates by **nearest-wins** (this depth-1 dep
beats ext-rpc's depth-2 transitive), **Gradle** selects the **highest version by default**
(project version > 1.0.3). `implementation` would already land in the published POM (runtime scope)
and fix resolution just as well — so `api` is not the only propagation path. It is the intentional
stronger compile+runtime exposure, chosen to mirror how `rpc-server` declares `rpc-api`/`rpc-common`
(consistency + compile visibility), matching how the server modules expose their peers.

No production source changed. `rpc-server-quarkus` does not compile against `rpc-client` (it
deliberately inlines `outputToJson`, see `AgentInvokeHandler.java` "inlined to avoid a client
dependency"); this dependency exists purely to align resolution for consumers who use both.

## Verification

### 1. Generated POM carries rpc-client at the project version (the whole point)

`gradle :rpc-server-quarkus:generatePomFileForMavenJavaPublication -Pversion=1.1.2-CLIENTDEP-TEST`
→ `rpc-server-quarkus/build/publications/mavenJava/pom-default.xml`:

```xml
<dependency>
  <groupId>tech.krpc</groupId><artifactId>rpc-server</artifactId>
  <version>1.1.2-CLIENTDEP-TEST</version><scope>compile</scope>
</dependency>
<dependency>
  <groupId>tech.krpc</groupId><artifactId>rpc-client</artifactId>   <!-- NEW -->
  <version>1.1.2-CLIENTDEP-TEST</version><scope>compile</scope>
</dependency>
<dependency>
  <groupId>tech.krpc</groupId><artifactId>http-server</artifactId>
  <version>1.1.2-CLIENTDEP-TEST</version><scope>compile</scope>
</dependency>
<dependency>
  <groupId>tech.krpc.ext</groupId><artifactId>ext-rpc</artifactId>
  <version>1.0.3</version><scope>runtime</scope>
</dependency>
```

The published POM now lists `tech.krpc:rpc-client:${version}` directly at `compile` scope.

### 2. Resolution proof (honest, real consumer)

Published the graph (`rpc-api`, `rpc-common`, `rpc-client`, `rpc-server`, `http-server`,
`rpc-server-quarkus`) to mavenLocal at `1.1.2-CLIENTDEP-SNAPSHOT`, then a scratch consumer:

```gradle
// /tmp/clientdep-consumer/build.gradle
repositories { mavenLocal(); maven { url 'https://maven.aliyun.com/repository/public/' }; mavenCentral() }
dependencies {
    implementation 'tech.krpc:rpc-server-quarkus:1.1.2-CLIENTDEP-SNAPSHOT'
    implementation 'tech.krpc.ext:ext-rpc:1.0.3'   // still pins rpc-client:1.0.3
}
```

`gradle dependencies --configuration runtimeClasspath`:

```
+--- tech.krpc:rpc-server-quarkus:1.1.2-CLIENTDEP-SNAPSHOT
|    +--- tech.krpc.ext:ext-rpc:1.0.3
|    |    \--- tech.krpc:rpc-client:1.0.3 -> 1.1.2-CLIENTDEP-SNAPSHOT   <-- old pin UPGRADED
|    +--- tech.krpc:rpc-server:1.1.2-CLIENTDEP-SNAPSHOT
|    +--- tech.krpc:rpc-client:1.1.2-CLIENTDEP-SNAPSHOT (*)             <-- direct decl wins
\--- tech.krpc.ext:ext-rpc:1.0.3 (*)
```

The transitive `ext-rpc → rpc-client:1.0.3` is upgraded to the project version. Both engines align:
- **Gradle** highest-wins: `1.1.2 > 1.0.3` → project version.
- **Maven** nearest-wins: the direct declaration (depth 1) beats ext-rpc's transitive (depth 2) → project version.

Before the fix the only `rpc-client` node was the transitive `ext-rpc → rpc-client:1.0.3` with
nothing to override it, so consumers resolved `1.0.3` (the drift). (Scratch consumer + test-version
mavenLocal artifacts removed after verification.)

### 3. Same-class audit of the other published entry-points

The trap = "reaches a `rpc-*` module ONLY transitively via a pinned external". The only pinned
external pulling a `rpc-*` module is `tech.krpc.ext:ext-rpc` (pins `rpc-client:1.0.3`). Repo-wide,
**only `rpc-server-quarkus` depends on `ext-rpc`** (grep of every module's `build.gradle`).

| Module (published) | rpc-* reach | Verdict |
| --- | --- | --- |
| `rpc-server-quarkus` | `rpc-client` was transitive-only via `ext-rpc:1.0.3` | **was same-class — FIXED** |
| `rpc-client-spring` | `api project(':rpc-client')` **directly** | aligned, no trap |
| `rpc-server-spring` | `api project(':rpc-server')`; rpc-server has no runtime `rpc-client` (test-only); no pinned external | never reaches rpc-client — no trap |
| `http-server` | `api project(':rpc-common')`; rpc-client/rpc-server test-only; no pinned external | no trap |
| `rpc-server` | `api rpc-api`/`rpc-common` (project); no pinned external | no trap |
| `rpc-api`, `rpc-common`, `rpc-client` | core/leaf; project deps only | no trap |
| `ext-rpc-gen` | `implementation project(':rpc-api'/':rpc-server')` only | project deps align — no trap |

No other module is same-class; no other alignment needed.

### 4. Gate

`gradle :rpc-server-quarkus:test :arch-test:test japicmpCheck --rerun-tasks --max-workers=2`
→ **BUILD SUCCESSFUL** (32 tasks executed).

- `:rpc-server-quarkus:test` — all classes `failures=0 errors=0 skipped=0` (17 + 9 + 5×2 + 6×2 + 3×2 + 1).
- `:arch-test:test` — 18 tests, `failures=0 errors=0`. The ARCH-001 scope guard / completeness
  guard did **not** trip: no production source changed, and the new `rpc-client` project dep
  resolves as a local `build/libs` artifact (owned), not an external cache jar.
- `japicmpCheck` — PATCH mode (1.1.1 vs baseline 1.1.1), additive-only; green. Adding a
  dependency does not touch the covered `rpc-api`/`rpc-common` public API surface.

## Completion notes

- **Changed:** `rpc-server-quarkus/build.gradle` (one `api project(':rpc-client')` + comment); this findings doc.
- **Validated:** generated POM (carries rpc-client at project version), real consumer resolution
  (ext-rpc old pin upgraded to project version), full gate green.
- **Not validated:** downstream published-Central resolution (uses test versions in mavenLocal; the
  published-POM shape is identical). Maven nearest-wins asserted by resolution rules, not run under Maven.
- **Source of truth / boundaries:** build-only change; no FOR/NOT-FOR boundary crossed; no ADR,
  roadmap, or module-evolution status change required. The `ext-rpc` version pin is unchanged.
- Commits LOCAL, no push. No AI signatures.
