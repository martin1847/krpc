# krpc benchmark — VT vs platform pool × JVM vs native (BENCH-001)

A **standalone** benchmark of published krpc, kept resident in the repo but built
in complete isolation.

> **Not part of the repo build.** This tree has its own `settings.gradle` and is
> deliberately *not* included in the root `settings.gradle`. `gradle build` at the
> repo root and the Maven Central release bundle never see it. Build/run it only
> from inside `benchmark/`.

It measures the RPC-microservice hot path — a DB-free, JWT-free small-message
unary (`bench/Hello/hello`) — against the **published krpc 1.1.0 on Maven Central**
(consumer view, not worktree source), across a 2×2 matrix:

|  | JVM | native |
|---|---|---|
| **VT** (`RPC_SERVER_DEFAULTEXECUTOR=false`, default) | ✓ | ✓ |
| **platform pool** (`=true`, grpc `DEFAULT_EXECUTOR_POOL`) | ✓ | ✓ |

Results, methodology, caveats, and the ADR-0002 reading: **[`RESULTS.md`](RESULTS.md)**.

## Layout

```
benchmark/
  settings.gradle       standalone build (includes only the 3 modules below)
  contract/             runtime-agnostic @RpcService interface + DTOs (rpc-api only)
  server-quarkus/       server under test — Quarkus runtime (the "server-<runtime>" slot)
  driver/               runtime-agnostic closed-loop load driver (LoadDriver)
  scripts/              build.sh + run_matrix.py (reproduce the 2×2)
  iouring/              io_uring PoC + harness + Linux runbook (reference only; see its README)
  RESULTS.md            append-only dated results
```

`contract/` and `driver/` depend only on published krpc + gRPC — no Quarkus, no
Spring — so they are reused verbatim by every server runtime.

## Prerequisites

- A **GraalVM JDK with `native-image`** on `JAVA_HOME` (built + measured on Oracle
  GraalVM 25.0.3). The JVM benchmarks and the host-local native build both use it.
- `gradle` 9.6.0 (the repo's line). `lsof` (driver port cleanup between runs).
- Docker is **not** needed for the 2×2 (only for the Linux-only `iouring/` runbook).

## Run

```bash
cd benchmark
export JAVA_HOME=/path/to/graalvm   # must contain bin/native-image
scripts/build.sh                    # JVM fast-jar + host-local native runner + driver classpath
python3 scripts/run_matrix.py       # 4 variants × {32,128,512} × 3 reps → build-logs/*.json
```

`run_matrix.py` prints a per-rep table and writes `build-logs/matrix_raw.json` +
`matrix_agg.json`. Fixed knobs (identical across all four): warmup 5 s, measured
10 s, 3 reps, concurrency ladder 32/128/512, single loopback gRPC channel.

Drive one variant by hand:

```bash
# start a server (VT default; add RPC_SERVER_DEFAULTEXECUTOR=true for the pool)
java -jar server-quarkus/build/quarkus-app/quarkus-run.jar          # JVM
./server-quarkus/build/server-quarkus-1.1.0-bench-runner            # native
# drive it: host port threads durationSec warmupSec label
java -cp "$(cat driver/build/driver-classpath.txt)" \
  tech.krpc.bench.driver.LoadDriver 127.0.0.1 50051 128 10 5 manual
```

### Native build fallback

The primary path builds native **host-local** (`container-build=false`) so all
four benchmarks share one loopback network path. If the host toolchain can't
build native-image, fall back to a **Mandrel container** build (SPEC §13.3):
`-Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21`.
If you take that fallback you **must run the JVM group in a matching container
too** — never mix a native-container server with a JVM-host server, or the runtime
axis is confounded by the network path. Record the choice in `RESULTS.md`.

## Adding a runtime (e.g. Spring Boot native — not yet scheduled)

The structure is multi-runtime by construction. To benchmark another runtime:

1. Add a sibling module `server-<runtime>/` (e.g. `server-spring/`) that implements
   the **same** `contract/` `HelloService` and exposes it on gRPC port 50051.
2. Register it in `settings.gradle`.
3. Add a variant row to `scripts/run_matrix.py` (`VARIANTS`) whose `cmd` launches it.
4. Reuse `contract/`, `driver/`, and the `RESULTS.md` table format **unchanged**.

No Spring code is pre-written here (YAGNI) — only the structural slot is reserved.
