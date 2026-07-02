# Security Policy

## Reporting a Vulnerability

**Do not open a public issue for security problems.** Use a private channel so a
fix can ship before the details are public.

- **Preferred — GitHub Private Vulnerability Reporting (PVR):** open a private
  report from the repository's **Security → Advisories → Report a vulnerability**
  page (`https://github.com/martin1847/krpc/security/advisories/new`). This routes
  into GitHub's advisory workflow, which produces a GHSA ID and downstream
  Dependabot/OSV signals once published.
- **Email fallback:** if you cannot use PVR, email the maintainer at
  `martin1847@gmail.com` with `[krpc-security]` in the subject.

Include: affected version(s), affected module(s), a minimal reproduction, and the
impact you observe. A CVSS estimate helps but is not required.

## Response Targets (solo-maintained project — honest expectations)

krpc is maintained by a single maintainer. Targets are best-effort, not a
contractual SLA:

| Stage | Target |
| --- | --- |
| Acknowledge report | within 7 days |
| Triage / severity assessment | best-effort after acknowledgement |
| Fix | best-effort; prioritised by severity |
| Coordinated disclosure window | up to 90 days from acknowledgement, or when a fix ships (whichever is first) |

Confirmed, GitHub-reviewed advisories are published with a GHSA ID and the correct
`tech.krpc:*` coordinates so downstream Dependabot/OSV consumers are alerted.

## Supported Versions

The version/support matrix lives in the [support policy](docs/support-policy.md).
For the krpc ↔ Quarkus LTS ↔ io.grpc alignment that governs native-image
consumers, [SPEC §13.1](SPEC.md#131-iogrpc-version-alignment--quarkus-lts-support-matrix)
is the **canonical** matrix. In short:

- The latest release always receives security fixes.
- The previous minor (N-1) receives **security fixes only**, for 90 days or until
  the aligned Quarkus LTS reaches EOL — whichever comes first.

## Scope

In scope — the published krpc runtime modules (group `tech.krpc`):

- `rpc-api`
- `rpc-common`
- `rpc-client`
- `rpc-server`
- `rpc-client-spring`
- `rpc-server-spring`
- `rpc-server-quarkus`
- `http-server`

Companion extensions `ext-rpc` and `ext-mybatis` (group `tech.krpc.ext`) release
on their own cycle; report issues in those through the same channels and name the
extension and version.

Out of scope (delegated by design — see [ADR-0001](docs/decisions/ADR-0001-repository-scope.md)):
service discovery, load balancing, telemetry, ingress TLS, and mesh policy belong
to Kubernetes / Istio / gateway infrastructure, not to the krpc runtime.

## Transport Security Model

krpc uses **gRPC over plaintext** by default: it carries east-west (service-to-
service) traffic and is not intended to face the public internet directly. TLS
termination belongs at the gateway layer
([Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/) via
Envoy/Nginx).

## Upstream Dependency Security Policies

- [gRPC Security Policy](https://github.com/grpc/grpc-java/blob/master/SECURITY.md)
- [gRPC CVE Process](https://github.com/grpc/proposal/blob/master/P4-grpc-cve-process.md)
- [Netty Security Policy](https://github.com/netty/netty?tab=security-ov-file)
