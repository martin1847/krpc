# io_uring transport — legacy PoC + Linux runbook (BENCH-001 §3)

**Status: reference material, not built and not re-run.** io_uring is a Linux
kernel feature; it cannot run on the macOS BENCH-001 host. This directory
**incorporates** the IOURING-001 evaluation artifacts (code + native metadata +
the original load harness) as a ready-to-rerun-on-Linux package, alongside a
runbook and the historical verdict. Nothing here is on the standalone benchmark
build's classpath (`settings.gradle` includes only `contract`, `server-quarkus`,
`driver`) — these files are inert provenance copies.

## Provenance

Copied verbatim (not moved) from branch `feat/iouring-eval`; that branch is left
untouched. Source commits:

- `0074f81` — feat(server): flag-gated io_uring transport PoC + native metadata + bench (IOURING-001 Phase 2)
- `95d1559` — fix(iouring): pure reflective transport + `-PwithIoUring` gating
- `c98ac02` — docs: io_uring evaluation findings (SPEC §13.4 note)

| copied file | origin on `feat/iouring-eval` | what it is |
|---|---|---|
| `transport/IoUringTransport.java` | `rpc-server/src/main/java/tech/krpc/server/IoUringTransport.java` | reflective transport selector — no compile-time io_uring/grpc-netty dependency; locates an `IoUringSupport` provider by name (`Class.forName`), so a build without io_uring gets the default transport and never `NoClassDefFoundError`s |
| `transport/IoUringSupport.java` | `test-server/src/iouring/java/tech/krpc/server/iouring/IoUringSupport.java` | the only class touching io_uring types (`NettyServerBuilder` + `IOUringServerSocketChannel` + event-loop) — present only in the `-PwithIoUring` source set |
| `transport/native-image/**` | `test-server/src/iouring/resources/META-INF/native-image/**` | hand-authored JNI / reflect / resource metadata for the archived `netty-incubator-transport-native-io_uring:0.0.26.Final` |
| `harness/LoadClient.java` | `test-server/src/test/java/test/krpc/bench/LoadClient.java` | the original IOURING-001 Phase 2 closed-loop load driver (drives `Demo/inc100` / `Demo/bytesTime`). BENCH-001's `driver/LoadDriver.java` is the adaptation of this harness to `bench/Hello/hello` + p50/p99/p999 |

## Historical verdict (do not re-run on macOS — cite this)

From `IOURING-001_BENCH_omp.md` (see pointers below), aarch64 in an OrbStack
Linux VM, `--security-opt seccomp=unconfined`, same `-PwithIoUring` image with
`KRPC_IOURING` toggled OFF (NIO) vs ON (io_uring), transport the only variable:

| config | method | RPS (med) | p50 µs | p99 µs |
|---|---|---|---|---|
| NIO (baseline) | inc100 | 22,830 | 654.6 | 2061.5 |
| io_uring | inc100 | 21,376 | 700.3 | 2148.8 |
| NIO (baseline) | bytesTime | 23,226 | 644.0 | 1695.3 |
| io_uring | bytesTime | 21,949 | 681.5 | 1809.8 |

**io_uring vs NIO (median): RPS −6.4% / −5.5%, p50 +7.0% / +5.8%.** Bundling
io_uring costs **+0.56 MiB image / +1.9 MiB RSS**. On krpc's typical
small-message unary path — few connections, HTTP/2 multiplexing — io_uring's
syscall-batching win case is barely exercised, so its fixed per-op ring overhead
is not repaid. The graduated `io.netty.channel.uring` transport (Netty 4.2 /
Quarkus 4 / Vert.x 5) may change this; the incubator artifact on Netty 4.1 is the
archived, less-optimized generation. **Recommendation stands: keep io_uring OFF
by default** (SPEC §13.4).

## Findings pointers (full detail — outside this repo)

The IOURING-001 orchestration docs live in the middleware workspace, not in the
krpc repo:

- `~/Garden/middleware/docs/orchestration/archive/IOURING-001_RESEARCH_omp.md` — research (Netty artifact constraint, CVE wave, native metadata authoring)
- `~/Garden/middleware/docs/orchestration/archive/IOURING-001_BENCH_omp.md` — PoC design + full numbers + honest interpretation
- krpc `SPEC.md` §13.4 — the shipped one-paragraph summary + ops note

## Linux runbook (reproduce, Linux only)

⚠️ **macOS cannot run this** (no io_uring). Requires a Linux host/VM with
`io_uring_disabled=0`. ⚠️ **Container numbers are not directly comparable to the
host-local BENCH-001 2×2 numbers in `../RESULTS.md`** — different transport,
different OS, VM-in-VM latency inflation. Use them only for the NIO-vs-io_uring
*delta* on the same image, never cross-referenced against the Hello 2×2.

```bash
# On feat/iouring-eval (the PoC lives there; this dir is a copy). Docker's default
# seccomp profile blocks io_uring_setup (EPERM) — BOTH configs run
# --security-opt seccomp=unconfined to isolate transport cost, not seccomp cost.
gradle :test-server:build -PwithIoUring -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21 \
  -Dquarkus.package.jar.enabled=false -x test
gradle :test-server:dumpTestCp   # writes build/testcp.txt + compiles LoadClient

# server: io_uring ON = -e KRPC_IOURING=1 ; NIO baseline = -e KRPC_IOURING=0 (same image)
docker run -d --name bench --security-opt seccomp=unconfined --cpuset-cpus=0-1 --memory=1g \
  -p 50051:50051 -e KRPC_IOURING=1 -e RPC_SERVER_APP=test-server \
  -v "$PWD/test-server/build:/app:ro" -w /app debian:stable-slim \
  ./test-server-1.0.2-runner -Drpc.server.app=test-server
java -cp "$(cat test-server/build/testcp.txt)" test.krpc.bench.LoadClient 127.0.0.1 50051 inc100 16 10 3
docker rm -f bench
```
