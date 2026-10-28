# ADR-0002: JDK 21 And Virtual Threads

Status: accepted

Date: 2026-05-30

## Context

KRPC targets modern cloud-native Java services. JDK 21 is the baseline. Virtual threads are a runtime feature, not a long-term plan.

## Decision

KRPC uses JDK 21 as its Java baseline.

Virtual threads are supported as a first-class runtime feature where they simplify request execution and reduce thread-pool complexity.

## Consequences

New Java runtime work may use JDK 21 APIs.

Virtual-thread usage should stay simple. It should not create a second runtime model unless a later ADR explains why.

## Evidence

BENCH-001 (2026-07-06, `benchmark/RESULTS.md`): on the small-message unary hot path, the VT per-task executor (default) beats the grpc platform cached pool on the JVM at every concurrency (+14.9% / +11.1% / +5.6% RPS at 32 / 128 / 512 threads) and in native at low concurrency (+10.5% at 32); the pool wins only in native at saturation (512 threads, +6.6%). The runtime flip (`RPC_SERVER_DEFAULTEXECUTOR`) is verified working on both JVM and native. VT as the default is evidence-backed for the typical case.
