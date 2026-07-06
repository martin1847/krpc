#!/usr/bin/env python3
"""BENCH-001 — run the VT-vs-pool x JVM-vs-native 2x2 and aggregate.

Faithful, self-contained version of the harness that produced ../RESULTS.md.
Runtime-agnostic: it starts a server variant, drives it with driver/LoadDriver,
and only the SERVER changes between variants (executor via RPC_SERVER_DEFAULTEXECUTOR,
runtime via which binary). Everything else — client transport, payload, host/port,
warmup, measured duration, concurrency ladder — is fixed across all four.

Adding a runtime (e.g. Spring): build a `server-spring` binary, add a variant row
whose `cmd` launches it, and reuse this script + driver + RESULTS format unchanged.

Prereq: scripts/build.sh has produced the JVM fast-jar, the native runner, and the
driver classpath. Run from benchmark/:  python3 scripts/run_matrix.py
"""
import json, os, pathlib, re, signal, statistics, subprocess, time

BENCH   = pathlib.Path(__file__).resolve().parent.parent
JAVA    = os.environ.get("JAVA_HOME", "") + "/bin/java" if os.environ.get("JAVA_HOME") else "java"
JVM_JAR = BENCH / "server-quarkus/build/quarkus-app/quarkus-run.jar"
NATIVE  = next(BENCH.glob("server-quarkus/build/*-runner"))
DRIVER_CP = (BENCH / "driver/build/driver-classpath.txt").read_text().strip()
LOGDIR  = BENCH / "build-logs"; LOGDIR.mkdir(exist_ok=True)
READY   = "RpcServer expose"
PORT    = 50051

# ---- fixed methodology knobs (identical for every benchmark) ----
WARMUP, DUR, REPS = 5, 10, 3
CONC = [32, 128, 512]

VARIANTS = [
    ("jvm-vt",      [JAVA, "-jar", str(JVM_JAR)], {"RPC_SERVER_DEFAULTEXECUTOR": "false"}),
    ("jvm-pool",    [JAVA, "-jar", str(JVM_JAR)], {"RPC_SERVER_DEFAULTEXECUTOR": "true"}),
    ("native-vt",   [str(NATIVE)],                {"RPC_SERVER_DEFAULTEXECUTOR": "false"}),
    ("native-pool", [str(NATIVE)],                {"RPC_SERVER_DEFAULTEXECUTOR": "true"}),
]


def kill_port(port=PORT):
    r = subprocess.run(["lsof", "-ti", f"tcp:{port}"], capture_output=True, text=True)
    for pid in r.stdout.split():
        try: os.kill(int(pid), signal.SIGKILL)
        except OSError: pass
    time.sleep(0.5)


def start_server(cmd, env_over, logname, ready_timeout=60):
    kill_port()
    env = dict(os.environ); env.update(env_over)
    logf = open(LOGDIR / logname, "w")
    p = subprocess.Popen(cmd, cwd=str(BENCH), env=env, stdout=logf, stderr=subprocess.STDOUT)
    t0 = time.time()
    while time.time() - t0 < ready_timeout:
        if READY in (LOGDIR / logname).read_text(): return p
        if p.poll() is not None: raise RuntimeError(f"server died: see {logname}")
        time.sleep(0.3)
    raise RuntimeError(f"server not ready in {ready_timeout}s: see {logname}")


def stop_server(p):
    if p and p.poll() is None:
        p.send_signal(signal.SIGTERM)
        try: p.wait(timeout=15)
        except subprocess.TimeoutExpired: p.kill()
    kill_port()


def run_driver(threads, label):
    cmd = [JAVA, "-cp", DRIVER_CP, "tech.krpc.bench.driver.LoadDriver",
           "127.0.0.1", str(PORT), str(threads), str(DUR), str(WARMUP), label]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=DUR + WARMUP + 120)
    return next((l for l in r.stdout.splitlines() if l.startswith("RESULT")), None)


def parse(line):
    d = {}
    for k, v in re.findall(r"(\w+)=([\d.\-]+|[\w\-]+)", line):
        d[k] = float(v) if ("." in v or k == "rps") else (int(v) if v.lstrip("-").isdigit() else v)
    return d


def main():
    matrix = {}
    for vname, cmd, env in VARIANTS:
        p = start_server(cmd, env, f"matrix_{vname}.log")
        print(f"### {vname}")
        matrix[vname] = {}
        for c in CONC:
            runs = []
            for rep in range(1, REPS + 1):
                line = run_driver(c, f"{vname}-c{c}-r{rep}")
                if not line: raise RuntimeError(f"no RESULT for {vname} c={c} r{rep}")
                d = parse(line); runs.append(d)
                print(f"  c={c:>3} r{rep}: rps={d['rps']:.0f} p50={d['p50us']:.0f} "
                      f"p99={d['p99us']:.0f} p999={d['p999us']:.0f} err={d['errors']}")
            matrix[vname][c] = runs
        stop_server(p)

    agg = {}
    for v in matrix:
        agg[v] = {}
        for c, runs in matrix[v].items():
            rps = [r["rps"] for r in runs]
            m = statistics.median(rps)
            agg[v][str(c)] = dict(
                rps=round(m), rps_spread_pct=round((max(rps) - min(rps)) / m * 100, 1),
                p50=round(statistics.median(r["p50us"] for r in runs)),
                p99=round(statistics.median(r["p99us"] for r in runs)),
                p999=round(statistics.median(r["p999us"] for r in runs)),
                errors=sum(int(r["errors"]) for r in runs))
    (LOGDIR / "matrix_raw.json").write_text(json.dumps(matrix, indent=2))
    (LOGDIR / "matrix_agg.json").write_text(json.dumps(agg, indent=2))
    print("\nwrote build-logs/matrix_raw.json + matrix_agg.json")


if __name__ == "__main__":
    main()
