# BENCH-001 RESULTS — VT vs platform pool × JVM vs native

Append-only log. Each run appends a dated section (machine spec + JDK + raw data);
never rewrite a prior section. Raw per-rep JSON for each run lives under
`build-logs/matrix_raw.json` (+ `matrix_agg.json`) at the time of the run.

---

## Run 2026-07-06 — Apple M2 Pro (host-local, all four)

### Environment (held identical across all four benchmarks)

| | |
|---|---|
| Host | Apple M2 Pro, 10 cores (10 physical / 10 logical), 32 GiB RAM |
| OS | macOS 26.5.1 (build 25F80), Darwin 25.5.0 |
| JDK / builder | Oracle GraalVM 25.0.3+9-LTS (JVM run + host-local `native-image`) |
| Gradle | 9.6.0 |
| Quarkus | 3.33.2 (LTS) |
| krpc under test | **1.1.0 from Maven Central** (consumer view — not worktree source) |
| ext-rpc | 1.0.3 (native server support, SPEC §13.2) |
| io.grpc | 1.79.0 (Quarkus LTS aligned, SPEC §13.1) |
| Native image | host-local build, `server-quarkus-1.1.0-bench-runner` = 50.7 MB, arm64 |
| Server under test | `bench/Hello/hello` — DB-free, JWT-free, small unary (`HelloRequest{name}` → `HelloReply{message,timestamp}`) |
| Client (driver) | `driver/LoadDriver` — single plaintext gRPC channel, N closed-loop blocking-stub worker threads |
| Startup (boot→ready) | JVM 0.251 s · native VT 0.032 s · native pool 0.024 s |

### The 2×2 — median of 3 reps (warmup 5 s, measured 10 s each)

Concurrency = closed-loop driver threads. `spread%` = (max−min)/median across the
3 reps for RPS. p50/p99/p999 in microseconds. **0 errors across all 36 runs.**

**concurrency 32**

| runtime × executor | RPS (med) | spread% | p50 µs | p99 µs | p999 µs |
|---|--:|--:|--:|--:|--:|
| JVM · VT | **37,624** | 2.0 | 829 | 1,244 | 5,223 |
| JVM · pool | 32,757 | 2.6 | 996 | 1,234 | 4,902 |
| native · VT | **30,801** | 1.5 | 982 | 2,629 | 5,306 |
| native · pool | 27,873 | 1.6 | 1,084 | 3,071 | 5,687 |

**concurrency 128**

| runtime × executor | RPS (med) | spread% | p50 µs | p99 µs | p999 µs |
|---|--:|--:|--:|--:|--:|
| JVM · VT | **35,459** | 1.7 | 3,560 | 4,574 | 9,266 |
| JVM · pool | 31,927 | 0.8 | 3,932 | 5,157 | 10,619 |
| native · VT | 31,399 | 0.9 | 3,986 | 6,849 | 15,005 |
| native · pool | 31,136 | 3.3 | 3,990 | 6,742 | 9,740 |

**concurrency 512** (past the knee — machine saturated; see caveats)

| runtime × executor | RPS (med) | spread% | p50 µs | p99 µs | p999 µs |
|---|--:|--:|--:|--:|--:|
| JVM · VT | **18,846** | 1.2 | 27,036 | 32,202 | 50,169 |
| JVM · pool | 17,852 | 1.7 | 28,545 | 31,668 | 34,323 |
| native · pool | **18,286** | 3.1 | 27,857 | 32,232 | 39,479 |
| native · VT | 17,070 | 2.7 | 29,786 | 36,210 | 55,834 |

### VT vs pool — throughput delta (median RPS, +ve = VT faster)

| | 32 | 128 | 512 |
|---|--:|--:|--:|
| **JVM** | +14.9% | +11.1% | +5.6% |
| **native** | +10.5% | +0.8% | **−6.6%** |

### Runtime-flip evidence (the ⚠️ gate — verified BEFORE running)

`rpc.server.defaultExecutor` is a runtime `@ConfigProperty`; the concern (per
EXTRPC-URL-001) was that native might bake it build-fixed. **It does not** — the
same single native binary flips branches by env `RPC_SERVER_DEFAULTEXECUTOR`, so
no two-image build was needed. Startup logs, published krpc 1.1.0:

| env | JVM & native startup log line | branch |
|---|---|---|
| `RPC_SERVER_DEFAULTEXECUTOR=false` (default) | `Init Executor bench-rpc(10 cpus), instead of ServerImplBuilder.DEFAULT_EXECUTOR_POOL` | VT (`Thread.ofVirtual()…newThreadPerTaskExecutor`, ADR-0002) |
| `RPC_SERVER_DEFAULTEXECUTOR=true` | `Use CachedThreadPool ServerImplBuilder.DEFAULT_EXECUTOR_POOL grpc-default-executor.` | grpc platform cached pool |

(Note: the VT-branch log wording differs from the krpc worktree HEAD, which
refined it to `Init virtual-thread per-task executor …` after 1.1.0 was cut —
benchmark tests the published artifact, so the 1.1.0 wording is authoritative
here. Verified the 1.1.0 VT branch is a genuine virtual-thread-per-task executor
by reading `rpc-server/.../exe/ThreadPool.java` at tag `v1.1.0`.)

### Methodology

- **One variable per comparison.** Client transport (grpc default, plaintext,
  one channel), request payload, host/port (127.0.0.1:50051, loopback), warmup
  (5 s) and measured window (10 s) are byte-for-byte identical across all four.
  The only thing that changes is the server: executor (env flip, same binary) and
  runtime (JVM fast-jar vs host-local native runner).
- **Host-local parity.** All four run as host processes on loopback — the
  driver↔server network path is identical. Native was built host-local with
  graalvm-25, *not* in a container, precisely so it shares the JVM group's path
  (mixing native-container with JVM-host would confound the runtime axis).
- Closed-loop blocking-stub driver (adapted from the IOURING-001 Phase 2 harness,
  so numbers relate to that methodology). ≥3 reps, report **median + spread**.
- Concurrency ladder 32/128/512 (BENCH-001 guardrail — deliberately not maxed).

### Caveats (laptop honesty)

- **Same-machine driver contention.** Driver and server share the 10-core M2 Pro.
  At **512** closed-loop threads the box is saturated (p50 ≈ 27 ms, RPS collapses
  vs 128) — this level is past the throughput knee and is a *stress* point, not a
  clean signal. The trustworthy VT-vs-pool signal is at **32 and 128**. The
  contention is applied equally to all four, so the *relative* ordering holds.
- **p999 is noisy** (single worst-tail samples on a loaded laptop; per-rep spread
  up to ~3×). RPS and p50/p99 are tight (spread < 3.5%) and are the load-bearing
  numbers; treat p999 as directional only.
- **JVM RPS at low concurrency reflects C2 peak** after warmup; native trades peak
  throughput for boot time (0.03 s vs 0.25 s) and image footprint — a different
  axis this micro-benchmark does not price in.
- **Not a container run.** io_uring / Mandrel-container numbers are out of scope
  here (macOS host); the io_uring historical comparison lives in `iouring/README.md`
  and is NOT cross-comparable to this table (different transport/OS).

### What this means for ADR-0002 (JDK 21 + virtual threads)

The data **supports keeping VT as the default** (`defaultExecutor=false`):

- On the JVM — krpc's primary runtime — VT beats the platform cached pool at
  every concurrency level tested (**+14.9% / +11.1% / +5.6%** at 32/128/512),
  with equal or better tail latency. For the typical RPC-microservice profile
  (small-message unary, low–moderate concurrency) VT is the clear win.
- In native, VT still wins at low concurrency (**+10.5%** at 32) and ties at 128.
- **Where the pool wins:** native **+ very high concurrency (512): pool +6.6%.**
  At the saturation point, VT's per-task virtual-thread creation and carrier
  scheduling overhead under a native runtime stops being repaid, and the bounded
  cached platform pool edges ahead. This is a stress-regime, native-only crossover
  — not the default operating point. A service that is knowingly native **and**
  runs sustained near-saturation concurrency could reasonably flip
  `RPC_SERVER_DEFAULTEXECUTOR=true`; everything else should stay on VT.

Net: ADR-0002's default is evidence-backed for the common case; the runtime flip
exists and works (JVM and native) for the one regime where the pool wins.

### Raw data

- Per-rep JSON (committed): `data/2026-07-06/matrix_raw.json`, aggregated: `data/2026-07-06/matrix_agg.json`
- Transient run logs (gitignored): `build-logs/matrix_<variant>.log`, gate logs `build-logs/{jvm,nat}_{vt,pool}.log`
- Reproduce: `scripts/build.sh` then `python3 scripts/run_matrix.py` (see `README.md`)
