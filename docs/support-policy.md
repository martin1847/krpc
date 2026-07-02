# KRPC Support Policy

This policy states, honestly, what a single maintainer can sustain. It is
deliberately narrower than large-vendor LTS promises (e.g. the JDK's 3-year
backports) — committing to multi-year backports as a solo project would be a lie.

## Supported Versions

- **The latest release is always supported** — it receives bug fixes and security
  fixes.
- **The immediately preceding release receives security fixes only**, for **90
  days** after its successor ships, **or until the aligned Quarkus LTS reaches
  EOL** — whichever comes first. krpc currently ships a single `1.0.x` minor
  line, so "the preceding release" today means the previous **patch** (1.0.2);
  once a second minor line exists, an older minor drops to security-only under
  this same rule.
- Older releases are **end of life**: no fixes, no security backports. Upgrade to a
  supported release.

## Version / Support Matrix

The **canonical** compatibility matrix (krpc ↔ Quarkus LTS ↔ io.grpc) is
[SPEC §13.1](../SPEC.md#131-iogrpc-version-alignment--quarkus-lts-support-matrix).
This page adds only the *support lifecycle* on top of that matrix; when the two
appear to differ, SPEC §13.1 wins for the version/alignment facts.

| krpc release | Status | Quarkus LTS | io.grpc | Notes |
| --- | --- | --- | --- | --- |
| 1.0.3 | Supported (latest) | 3.33.x LTS | 1.79.0 | Aligned to the Quarkus LTS BOM — no consumer-side grpc force. |
| 1.0.2 | Security fixes only (previous patch) | 3.33.x LTS | 1.82.0 | Ships io.grpc 1.82.0 (above the BOM); native-image consumers must force io.grpc back to 1.79.0 — SPEC §13.1. |
| ≤ 1.0.1 | End of life | — | — | Upgrade. |

## Anchoring: the Quarkus LTS Train

KRPC's support window is anchored to the **Quarkus LTS release train**, not to a
fixed calendar cadence. The currently targeted train is **Quarkus 3.33 LTS**,
with community LTS support running to approximately **2027-03**
([Quarkus LTS releases](https://quarkus.io/blog/lts-releases/)). When KRPC adopts
the next Quarkus LTS, `grpcVersion` is bumped in lockstep (ADR NATIVE-001,
Option A) and this matrix is updated.

Anchoring to the Quarkus LTS gives downstream consumers a predictable EOL they can
plan around, without the maintainer having to promise an independent multi-year
backport line.

## Security Fixes

Security reporting, response targets, and disclosure windows are in
[SECURITY.md](../SECURITY.md). Security fixes for the preceding release follow the window
above; confirmed advisories are published with a GHSA ID and correct `tech.krpc:*`
coordinates so downstream Dependabot/OSV consumers are alerted.

## Extensions

`ext-rpc` and `ext-mybatis` (group `tech.krpc.ext`) release on their own cycle and
are not covered by this matrix. Their compatibility requirements are tracked in
SPEC §13 (native image) where relevant (e.g. `ext-rpc` ≥ 1.0.2 for server-side
native support).

---

_Mirror note: a copy of the lifecycle summary may be published to
[endoflife.date](https://endoflife.date/) for discoverability; SPEC §13.1 and this
page remain the source of truth._
