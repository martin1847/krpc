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
