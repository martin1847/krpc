# KRPC Support Policy

This policy states, honestly, what a single maintainer can sustain. It is
deliberately narrower than large-vendor LTS promises (e.g. the JDK's 3-year
backports) — committing to multi-year backports as a solo project would be a lie.

## Supported Versions

- **The latest release is always supported** — it receives bug fixes and security
  fixes.
- **The immediately preceding release receives security fixes only**, for **90
  days** after its successor ships, **or until the aligned Quarkus LTS reaches
  EOL** — whichever comes first. krpc now ships three minor lines (`1.0.x`,
  `1.1.x`, `1.2.x`), so "the preceding release" today means the last `1.1.x`
  patch (**1.1.1**).
- Older releases are **end of life**: no fixes, no security backports. Upgrade to a
  supported release.

## Version / Support Matrix

The **canonical** compatibility matrix (krpc ↔ Quarkus LTS ↔ io.grpc) is **§13.1**,
which lives in
[`skills/krpc/references/native-image.md`](../skills/krpc/references/native-image.md#131-iogrpc-version-alignment--quarkus-lts-support-matrix)
— `SPEC.md §13` keeps the day-1 consumer checklist and delegates the recipes and
the version matrix to that file. This page adds only the *support lifecycle* on top
of that matrix; when the two appear to differ, §13.1 wins for the version/alignment
facts.

| krpc release | Status | Quarkus LTS | io.grpc | Notes |
| --- | --- | --- | --- | --- |
| 1.2.0 (2026-08-18) | Supported (latest) | 3.33.x LTS | 1.79.0 | Aligned to the Quarkus LTS BOM — no consumer-side grpc force. |
| 1.1.1 (2026-07-18) | Security fixes only, until **2026-11-16** | 3.33.x LTS | 1.79.0 | Last `1.1.x` patch. The window is 90 days after 1.2.0 shipped; that lands before the Quarkus 3.33 LTS EOL, so the 90-day clock is the binding one. |
| 1.1.0, 1.0.3 | End of life | 3.33.x LTS | 1.79.0 | Superseded patches. Upgrade. |
| 1.0.2 | End of life | 3.33.x LTS | 1.82.0 | Ships io.grpc 1.82.0 (above the BOM); a native-image consumer still on this release must force io.grpc back to 1.79.0 — §13.1. |
| ≤ 1.0.1 | End of life | 3.33.x LTS | 1.82.0 | Same alignment as 1.0.2 — io.grpc above the BOM, so a native-image consumer must force io.grpc back to 1.79.0 (§13.1). Upgrade. |

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

## Extensions — the `ext-*` compatibility contract

`ext-rpc` and `ext-mybatis` (group `tech.krpc.ext`) release on their own cycles, and
their version numbers move independently of the core's. The authority for what
combines with what is therefore **the `ext-*` versions each core release declares and
tests** — not the extension's own version number, and not the core version an
extension repo happened to compile against:

> An `ext-*` release is supported only with the krpc core **minor line that declares
> and tests it**. Combining an `ext-*` build with any other core minor is outside the
> supported surface: it may work, but it is not tested and gets no fixes.

| krpc core line | ext-rpc declared + tested | ext-mybatis declared + tested |
| --- | --- | --- |
| 1.2.x | 1.1.0 | 1.0.2 |
| 1.1.x | 1.0.3 | 1.0.1 |
| 1.0.2 – 1.0.3 | 1.0.1 | not declared |
| 1.0.0 – 1.0.1 | 1.0.0 | not declared |

Where each cell comes from, so a consumer can check it without asking:

- **`ext-rpc` is transitive, and therefore consumer-facing.**
  `tech.krpc:rpc-server-quarkus` declares the version above as a `runtime`
  dependency, so the pairing *is* what a consumer resolves by default. Do not
  override it.
- **`ext-mybatis` is not a core dependency** — nothing in the published core pulls
  it. The pairing is the `extMybatisVersion` that core release was built and tested
  with, and the consumer picks the version explicitly. The `extMybatisVersion`
  property only exists from krpc **1.1.0** onward (the ext properties were split so
  the two extensions version independently), hence "not declared" on the 1.0.x rows.
- **An extension's own `rpcVersion` pin is a different relation — do not read it as
  the contract.** It is the core version that extension repo compiles and tests
  against (compile-only ABI alignment), and it does not track the core release that
  ships the extension: `ext-rpc` 1.1.0 was built with `rpcVersion = 1.1.1` yet is the
  version core 1.2.0 declares, and `ext-mybatis` 1.0.2 was built with
  `rpcVersion = 1.0.3` yet is what core 1.2.x declares. The table is the supported
  combination; the pin is an implementation detail of the extension build.
- Since **`ext-rpc` 1.1.0** the extension publishes with **zero `tech.krpc`
  dependencies** (deliberately, to break the cross-repo release cycle), so nothing
  in Maven resolution enforces the pairing in the ext → core direction. It is a
  support statement, not a constraint the build will fail on.

Native-image requirements for the extensions (e.g. `ext-rpc` ≥ 1.0.2 for
server-side native support) are in §13.2 of
[`skills/krpc/references/native-image.md`](../skills/krpc/references/native-image.md).

---

_Mirror note: a copy of the lifecycle summary may be published to
[endoflife.date](https://endoflife.date/) for discoverability; §13.1 (in
`skills/krpc/references/native-image.md`) and this page remain the source of truth._
