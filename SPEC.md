# KRPC Development Spec

A tool-agnostic handbook for any coding agent (Claude, Codex, …) **writing or
calling KRPC services**. It encodes the framework's authoring conventions with
code evidence (`file:line`). Governance/architecture authority lives in
[`AGENTS.md`](AGENTS.md) + ADRs + `docs/`; when they conflict with this file,
they win. Build/release is in [§12](#12-build-test-release).

KRPC is **interface-first**: you write a Java interface + DTOs; the framework
handles transport (gRPC/HTTP2), JSON serialization, validation, metadata, and
client generation. Service authors write **no proto files**.

---

## 1. The method contract (the one rule that bites first)

Every RPC method MUST be:

```java
RpcResult<SomeDto> methodName(OneDto req)   // exactly one param
RpcResult<SomeDto> methodName()             // or zero params
```

Two hard constraints, both enforced at the **only** method-discovery point
(`rpc-common/.../util/RefUtils.java:117-120`):

```java
.filter(m -> m.getReturnType() == RpcResult.class && m.getParameterCount() <= 1)
```

- **Return type must be `RpcResult<…>`.**
- **At most one parameter.**

A method violating either is **silently dropped** — not registered, no error at
startup; the call just fails to resolve at runtime. This is the single most
common mistake.

- **DO:** merge multiple inputs into one DTO. Built-in generic wrappers exist:
  `PagedQuery<T>` (`rpc-api/.../PagedQuery.java`), e.g. `plistBk(PagedQuery<Book> q)`.
- **DON'T:** `m(Long id, String name)` (two params) or `SomeDto m(...)` (raw return).

The wire protocol has a single input slot; the server reads only
`inputArgTypes[0]` (`rpc-server/.../UnaryMethod.java:70-71`).

---

## 2. RpcResult — the response envelope

`rpc-api/.../model/RpcResult.java`. Three fields, success/failure mutually
exclusive:

| field | meaning |
| --- | --- |
| `int code` | `0` = OK; `> 0` = business error. **No negative codes.** |
| `String msg` | non-null only when `code > 0` |
| `DTO data` | non-null only when `code == 0` |

```java
return RpcResult.ok(data);          // asserts data != null
return RpcResult.error(666, "...");  // asserts code > 0 && msg != null
result.isOk();                       // code == 0
result.ifOk(fn); result.orElseThrow();
```

Business error codes are **bucketed by hundreds; large domains by thousands**
(`RpcResult.java:21-22`). The numeric space is a superset of `google.rpc.Code`
(see `CommonCode`).

Callers read `data` only after `isOk()` (`rpc-client/.../ClientResult.java:32-41`).

- **DO:** `ok(nonNullData)` / `error(positiveCode, msg)`.
- **DON'T:** return a bare DTO, use negative codes, set `msg` on success or
  `data` on failure, or call `ok(null)` (assertion fails).

---

## 3. Error model — soft vs hard exceptions

Two **different channels**. Pick deliberately.

### Soft (business failure) → return, don't throw
Expected business failures return through `RpcResult.code`. The client gets a
normal `RpcResult` with `isOk()==false`.

```java
public RpcResult<Integer> testLogicError(Integer i) {
    return RpcResult.error(666, "业务逻辑失败");   // DemoServiceImpl.java:43-46
}
```
`RpcResult.java:22-24` is explicit: **do not use Java exceptions to convey
business errors — define an error code instead.**

### Hard (system/security/validation) → throw
System errors, auth failures, validation failures, and unexpected
`RuntimeException`s are thrown. The server catches everything
(`UnaryMethod.java:212-231`): a `StatusException`/`StatusRuntimeException` passes
through as-is; anything else is wrapped in `Status.UNKNOWN` with the message
**truncated to 100 chars** and an error log keyed by `traceId`.

| | Soft | Hard |
| --- | --- | --- |
| trigger | business rule unmet | system / security / validation / unexpected |
| express | `return RpcResult.error(code,msg)` | `throw` (prefer `Status.X.withDescription(..).asRuntimeException()`) |
| code | business code (hundreds/thousands) | gRPC Status code |
| client sees | normal `RpcResult`, `!isOk()` | gRPC `onError` / `StatusRuntimeException` |
| logged | no | `log.error(traceId, ex)` |

- **DON'T:** throw for business errors; rely on exception messages reaching the
  client intact (they're truncated to 100 chars).
- Document codes with `@Doc.ErrorCode(code=, when=, message=)` (`Doc.java:37-52`).

---

## 4. DTO rules

### Use boxed types, never primitives
JSON serialization is `NON_NULL` (`rpc-common/.../util/JsonUtils.java:24-26`):
null fields are **omitted**. Primitives can't be null and serialize as `0`/`false`,
destroying the "absent vs zero" distinction.

```java
public class Book {            // Book.java:10-17
    Integer id; Float lng; Float lat;   // boxed, not int/float
}
```
- **DO:** `Integer/Long/Boolean/Float/Double` for all scalar DTO fields.
- **DON'T:** `int/long/boolean` — loses null semantics, breaks convention.

### Type avoidance
- **Avoid `Map`** in responses (functional tests only; not for production —
  `DemoService.java:41`).
- **Avoid `enum`** in response DTOs — adding/removing values breaks old clients
  (`README:87`).
- **`byte[]`** is the one type passed bare on the wire, but **TS/Dart clients
  cannot send `byte[]` as input** (`DemoService.java:33`).
- Generic DTOs are supported (`PagedQuery<T>`, `PagedList<T>`, `List<T>`); the
  server resolves type args by reflection on the method signature.

### Deserialization is lenient
`FAIL_ON_UNKNOWN_PROPERTIES=false` — extra fields from clients are tolerated
(forward compatibility). `JavaTimeModule` auto-registers if jsr310 is present.

---

## 5. @RpcService and service naming

`@RpcService` on the **interface** (`rpc-api/.../annotation/RpcService.java`).
Attributes: `value` (override name), `version`, `description`, `expireSeconds`.

Name derivation (`RefUtils.java:126-151`):
- strip leading `I`: `IDemoService` → `Demo`
- strip `Service`/`Rpc` suffix: `DemoService` → `Demo`, `FooRpc` → `Foo`
- full name = `appName/ServiceName` (e.g. `test-server/Demo`)
- **no dots** allowed in a service name

Call path is `app/Service/method`.

---

## 6. @UnsafeWeb — web exposure + hiding

`@UnsafeWeb` (TYPE-level, `rpc-api/.../annotation/UnsafeWeb.java`) marks a service
**reachable directly by browsers/frontend**. Without it, the service name is
prefixed with `-` (`HIDDEN_SERVICE`, `RefUtils.java:124-150`) so the HTTP gateway
keeps it service-to-service only.

```java
@UnsafeWeb                              // frontend-reachable
@UnsafeWeb(requireCredential = true)    // + JWT required for all methods
@UnsafeWeb.RequireCredential            // method-level JWT requirement
```
Credential precedence: method `@RequireCredential` > class `requireCredential` >
default false (`UnaryMethod.java:58-64`). When required, `ctx.checkCredential()`
runs and throws (hard) on failure.

- **DO:** add `@UnsafeWeb` only to services the frontend must call directly.
- **DON'T:** add it to internal services (exposes them publicly). The name
  "Unsafe" is a reminder that **you** own the security hardening.

---

## 7. Validation

Uses **`jakarta.validation`** (not `javax.validation` — only the `jakarta`
prefix is scanned, `RefUtils.java:158-175`). A validator is attached only when a
DTO has constraint annotations; failures throw `INVALID_ARGUMENT` (hard,
`ValidatorInvoke.java:31-44`).

```java
public class PagedQuery<Q> {                 // PagedQuery.java:28-36
    @NotNull @Min(1) Integer page;
    @Valid Q q;                              // @Valid cascades to nested DTOs
}
```
- **DO:** annotate with `jakarta.validation` constraints; add `@Valid` for nested
  cascade.

---

## 8. Authentication / context

JWT from `Authorization: Bearer <jwt>`, else from cookie
(`CredentialVerify.java:34-59`). In a service impl, read the user via
`ServerContext` (`ServerContext.java:138-151`):

- `ctx.uid()` — required; throws if not authenticated.
- `ctx.softUid()` — optional; returns null if not authenticated.

- **DO:** `uid()` for must-be-logged-in endpoints; `softUid()` for optional auth.

---

## 9. Other authoring annotations

- **`@Doc`** (`Doc.java`) — TYPE/FIELD/METHOD docs fed into generated clients;
  `hidden=true` omits from clients; nested `@ErrorCode` documents business codes.
- **`@Cached`** (`Cached.java`) — client-side cache by `expireSeconds` (method >
  `@RpcService.expireSeconds` > manager default); **only `code==OK` results are
  cached** (`MethodCallProxyHandler.java:148`).

---

## 10. Service implementation

Implement the interface (optionally via an `AbstractXxxService` base for shared
defaults). Container wiring:

- **Quarkus:** `@ApplicationScoped @Startup` (`DemoServiceImpl.java:19-22`).
- **Spring:** see `rpc-server-spring`.

The gRPC/Netty server executor runs on **virtual threads** (one named virtual
thread per RPC, `rpc-server/.../exe/ThreadPool.java`; JDK 21 / ADR-0002) —
handlers may block on IO freely; don't add your own bounded RPC thread pool.

---

## 11. Wire / JSON / tracing (mostly automatic)

- Wire envelope `OutputProto` uses single-letter fields `c`(code)/`m`(msg)/`bs`(bytes);
  null `msg`/`data` are not written (`ServerResult.java:30-38`).
- Trace propagation is automatic: server injects B3 MDC
  (`X-B3-TraceId/SpanId`), client forwards the MDC `traceId`
  (`ServerContext.java:86-94`, `MethodCallProxyHandler.java:123-129`). Authors
  don't manage it.

---

## 12. Build, test, release

```bash
./gradlew clean build -x test    # -x test: integration tests need an internal
                                 # MySQL host absent in clean envs and will hang
./gradlew allDeps
```
JDK 21 baseline; pinned Gradle wrapper 8.14.5. When you skip tests, say so and
list what was not validated.

Native (container build, Mandrel/JDK 25):
```bash
./gradlew :test-server:build -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  -Dquarkus.package.jar.enabled=false -x test
```

Release to Maven Central (group `tech.krpc`; credentials in
`~/.gradle/gradle.properties`, never in the repo):
```bash
GRADLE_CMD=./gradlew gradle/publish-central.sh upload        # build+sign+upload, wait VALIDATED (reversible)
GRADLE_CMD=./gradlew gradle/publish-central.sh publish --yes  # IRREVERSIBLE — cannot be deleted/overwritten
```
Set `version` + `changelog.md` first; `ext-rpc-gen` (group `tech.krpc.ext`) is
released on its own cycle, not in the krpc bundle. Tag `vX.Y.Z` and cut the
GitHub release after Central publish succeeds.

---

## 13. Quick checklist for a new service

- [ ] Interface annotated `@RpcService`; name has no dots.
- [ ] Every method `RpcResult<Dto> m(OneDto)` or `m()` — never 2+ params, never raw return.
- [ ] DTO scalar fields are boxed types; no `Map`/`enum` in responses.
- [ ] Business failures → `RpcResult.error(code>0, msg)` with hundreds/thousands codes; system/security → throw.
- [ ] `@UnsafeWeb` only if frontend-facing; `requireCredential` where auth is needed.
- [ ] `jakarta.validation` constraints (+ `@Valid` for nesting) where input must be checked.
- [ ] `ctx.uid()` / `ctx.softUid()` for the user; don't hand-roll trace propagation.

---

**Source of truth:** ADRs (`docs/decisions/`) > module docs (`docs/modules/`,
`FOR`/`NOT FOR`) > module evolution > roadmap (`docs/roadmap/active-roadmap.md`)
> inline TODOs. Index: `docs/INDEX.md`. Governance: `AGENTS.md`.
