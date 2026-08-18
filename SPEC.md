# KRPC Development Spec

<!-- CANONICAL. This root SPEC.md is the source of truth. A byte-identical copy is
     bundled at skills/krpc/references/SPEC.md so the `krpc` agent skill stays
     self-contained when copied into a consumer project — edit THIS file; CI
     (.github/workflows/skill-sync.yml) fails if the mirror drifts.
     LENGTH IS GATED: .githooks/pre-push check ③ caps both copies. Keep this file
     normative — statements of behavior, tables, file:line anchors. Rationale,
     migration history and "why we changed it" belong in changelog.md / ADRs. -->

A tool-agnostic handbook for any coding agent (Claude, Codex, …) **writing or calling KRPC
services**. It encodes the framework's authoring conventions with code evidence (`file:line`).
Governance/architecture authority lives in [`AGENTS.md`](AGENTS.md) + ADRs + `docs/`; when they
conflict with this file, they win. Build/release is in [§12](#12-build-test-release).

KRPC is **interface-first**: you write a Java interface + DTOs; the framework handles transport
(gRPC/HTTP2), JSON serialization, validation, metadata, and client generation. Service authors
write **no proto files**.

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

A method violating either is **rejected fail-fast at discovery** (HARDEN-B3): an *abstract*
`@RpcService` method with an illegal signature throws `IllegalStateException` from
`RefUtils.toRpcMethods` (server startup / client stub init / codegen). `default`/`static` methods
(and Object redeclarations) are exempt — the sanctioned escape hatch for interface helpers.

- **DO:** merge multiple inputs into one DTO. Built-in generic wrappers exist: `PagedQuery<T>`
  (`rpc-api/.../PagedQuery.java`), e.g. `plistBk(PagedQuery<Book> q)`.
- **DON'T:** `m(Long id, String name)` (two params) or `SomeDto m(...)` (raw return).

The wire protocol has a single input slot; the server reads only `inputArgTypes[0]`
(`rpc-server/.../UnaryMethod.java:70-71`).

---

## 2. RpcResult — the response envelope

`rpc-api/.../model/RpcResult.java`. Three fields, success/failure mutually exclusive:

| field | meaning |
| --- | --- |
| `int code` | `0` = OK; `> 0` = business error. **No negative codes.** |
| `String msg` | non-null only when `code > 0` |
| `DTO data` | non-null only when `code == 0` |

```java
return RpcResult.ok(data);          // throws NPE if data == null
return RpcResult.error(666, "...");  // throws IllegalArgumentException if code <= 0, NPE if msg == null
result.isOk();                       // code == 0
result.ifOk(fn);                     // maps OK data → a NEW RpcResult (never mutates the receiver)
result.orElseThrow();
```

> HARDEN-B3: `ok`/`error` enforce these invariants at runtime; `ifOk` builds a **new** result
> rather than re-typing + mutating `this`; the no-arg `error()` reinterpret-as-failure throws
> `IllegalStateException` on an OK result.

Business error codes are **bucketed by hundreds; large domains by thousands**
(`RpcResult.java:21-22`); the numeric space is a superset of `google.rpc.Code` (see `CommonCode`).
Callers read `data` only after `isOk()` (`rpc-client/.../ClientResult.java:32-41`).

- **DO:** `ok(nonNullData)` / `error(positiveCode, msg)`.
- **DON'T:** return a bare DTO, use negative codes, set `msg` on success or
  `data` on failure, or call `ok(null)`.

---

## 3. Error model — soft vs hard exceptions

Two **different channels**. Pick deliberately.

### Soft (business failure) → return, don't throw
Expected business failures return through `RpcResult.code`; the client gets a normal `RpcResult`
with `isOk()==false`.

```java
public RpcResult<Integer> testLogicError(Integer i) {
    return RpcResult.error(666, "业务逻辑失败");   // DemoServiceImpl.java:43-46
}
```
`RpcResult.java:22-25` is explicit: **do not use Java exceptions to convey business errors —
define an error code instead.**

**Numbering (a suggestion, not a rule): start business codes at 1000.** gRPC status occupies 0–16
and 17–999 is reserved for system codes krpc may add later, so a code ≥ 1000 cannot collide with
either. Band widths are unchanged: hundreds per business area (1000–1099, 1100–1199, …), thousands
for a large one. **The framework does not act on this** — nothing validates, reserves or routes on
the range; a code below 1000 works exactly as before. Do not write code that depends on it.

### Hard (system/security/validation) → throw
System errors, auth failures, validation failures, and unexpected `RuntimeException`s are thrown.
The server catches everything (`UnaryMethod.toClientError`): a
`StatusException`/`StatusRuntimeException` passes through as-is; anything else is wrapped in
`Status.UNKNOWN` with the message **truncated to 100 chars**.

**Two caveats before you reach for `Status.X.withDescription(...)`**, both detailed in §4:

- **The description does not always reach the client.** On gRPC it does; on the agent faces only
  the request-refusal codes (`INVALID_ARGUMENT`, `NOT_FOUND`, `ALREADY_EXISTS`,
  `FAILED_PRECONDITION`, `OUT_OF_RANGE`) pass it through — every other code, `INTERNAL` included,
  is replaced with a generic string. To have the caller read your text, model the failure as a
  refusal code or return a soft `RpcResult.error`.
- **Not every throw is logged with a stack** — only failures classified as the server's
  (`INTERNAL`/`UNKNOWN`/`DATA_LOSS`/`ABORTED`/`DEADLINE_EXCEEDED` and anything unrecognised). A
  refused request is a bounded one-line WARN.

| | Soft | Hard |
| --- | --- | --- |
| trigger | business rule unmet | system / security / validation / unexpected |
| express | `return RpcResult.error(code,msg)` | `throw` (prefer `Status.X.withDescription(..).asRuntimeException()`) |
| code | business code (hundreds/thousands) | gRPC Status code |
| client sees | normal `RpcResult`, `!isOk()` | gRPC `onError` / `StatusRuntimeException` |
| logged | no | server fault: `log.error` + stack; refusal: bounded WARN (§4) |

- **DON'T:** throw for business errors; rely on exception messages reaching the client intact
  (they're truncated to 100 chars).
- Document codes with `@Doc.ErrorCode(code=, when=, message=)` (`Doc.java:37-52`).

---

## 4. DTO rules

### Use boxed types, never primitives
JSON serialization is `NON_NULL` (`rpc-common/.../util/JsonUtils.java:68`): null fields are
**omitted**. Primitives can't be null and serialize as `0`/`false`, destroying the
"absent vs zero" distinction.

```java
public class Book {            // Book.java:10-17
    Integer id; Float lng; Float lat;   // boxed, not int/float
}
```
- **DO:** `Integer/Long/Boolean/Float/Double` for all scalar DTO fields.
- **DON'T:** `int/long/boolean` — loses null semantics, breaks convention.

### Type avoidance
- **Avoid `Map`** in responses (functional tests only; not for production — `DemoService.java:41`).
- **Avoid `enum`** in response DTOs — adding/removing values breaks old clients (`README:87`).
- **`byte[]`** is the one type passed bare on the wire, but **TS/Dart clients cannot send
  `byte[]` as input** (`DemoService.java:33`).
- Generic DTOs are supported (`PagedQuery<T>`, `PagedList<T>`, `List<T>`); the server resolves
  type args by reflection on the method signature.

### Collection fields — use `List<T>`, not arrays
- Use **`List<T>`** for a sequence field; Java arrays are **UNSUPPORTED** — the contract meta does
  not model array component types, so generation would reference an undeclared type. The meta scan
  **fails fast** on an object-array field (`Zebra[]`, `String[]`), naming the declaring DTO + field
  (`RpcMetaServiceImpl.checkFieldContract`).
- **Exemption:** a **single-dimension** array of a *primitive* (`byte[]`, `int[]`) is allowed — the
  binary/scalar-payload convention (`test-api/.../dto/Img.java:30`). **Multi-dimensional** arrays
  (`int[][]`) and object arrays are UNSUPPORTED — use `List<T>` / `List<List<T>>`.
- **`Map<K,V>` is NOT RECOMMENDED** — generated client code loses readability; model the shape as an
  explicit DTO class. Not an error: the scan logs a WARN (deduped per DTO field).

### Unknown fields are tolerated; scalar types are not
`FAIL_ON_UNKNOWN_PROPERTIES=false` — extra fields from clients are tolerated (forward
compatibility). `JavaTimeModule` auto-registers if jsr310 is present.

**Scalar decoding is strict, and this is the default since 1.2.0.** A JSON **number or
boolean** sent into a `String` target is a **decode failure**. It is not stringified:

```
{"phone": 13800138000}   → rejected   (send {"phone": "13800138000"})
{"flag":  true}          → rejected   (into a String field)
{"phone": []} / {}       → rejected   (unchanged; these always failed)
```

> **BREAKING in 1.2.0.** Through 1.1.1 the first two were silently coerced. Affected: any caller
> sending a JSON number/boolean into a DTO `String` field — rejected on deploy with no change on
> its side; client-side response decoding is in scope too. **Migration:** enumerate the `String`
> fields on your request DTOs, audit what reaches them, send JSON strings. If the audit cannot
> finish on the deploy timeline, set `KRPC_JSON_STRICT=false`, ship, then fix and remove it — the
> kill switch restores pre-1.2.0 decoding *including* the failure mode this change removes. Why a
> minor and not a patch: §14.1. Full rationale and blast radius: **changelog.md 1.2.0**.

**The kill switch** — one environment variable, no system-property equivalent (a kill switch must
be settable from a deployment manifest without touching the JVM command line):

| `KRPC_JSON_STRICT` | decoding |
| --- | --- |
| unset | **strict** (the default) |
| blank / whitespace (Java `isBlank()`) | **strict** — blank is treated as unset |
| `true` / `1` (case-insensitive, trimmed) | **strict** — explicitly, a valid affirmation |
| `false` / `0` (case-insensitive, trimmed) | lenient — the documented escape |
| anything else (`fasle`, `yes`, …) | lenient |
| environment unreadable (restricted JVM) | lenient |

- **Blank keeps strict** — an empty value is far more often an unsubstituted template variable than
  a decision to disable the guard. Same blank-means-unset rule as `APP_ENV` and `KRPC_OTEL`.
- **`isBlank()`/`trim()` are Java's** — ASCII whitespace only, **not** NBSP (`U+00A0`), figure space
  (`U+2007`) or narrow NBSP (`U+202F`). A value carrying one (`"true\u00A0"`, what pasting an
  affirmative out of a rendered doc gives you) is neither blank nor a recognised token, so it falls
  to the lenient row and turns strict decoding **off, silently** — type it, don't paste it.
- **An unrecognised value falls to lenient**, deliberately the opposite of a feature flag: whoever
  sets this is mid-incident and a typo must land somewhere recoverable. `true`/`1` still mean
  "keep strict", so the intuitive spelling of *enabling* the guard cannot disable it.

Resolution is `JsonUtils.strictTextualCoercion` (`JsonUtils.java:160`); the variable name is
`JsonUtils.STRICT_TEXTUAL_COERCION_ENV` (`:50`).

**What strict covers** — every decode through `JsonUtils.parse` into a *typed* target, not just
request DTOs and not just the server: gRPC request decode (`JsonSerial.readInput`); **client-side
response decode** (`JsonSerial.readOutput` → `ClientResult`), so a caller on 1.2.0 also reads its
callee's reply strictly; `/agent/invoke` twice (outer envelope, then the target method's own typed
decode); MCP `tools/call` (arguments are re-serialized through the same path). The target need not
be a DTO *field* — a top-level `String`, `List<String>` or `Map<String,String>` is a textual target
too, so members of those collections are covered.

**What strict does not cover.** Serialization (`stringify` always uses the lenient mapper) and
untyped `Map`/`Object` decoding, which has no textual target so nothing coerces — hence
`JwsCredential`'s JWT header/payload reads and `McpHandler`'s envelope reads are unaffected.

**There is no lenient back door.** krpc has one decode entry point and it honours the switch; no
internal call site opts itself out. RFC 7517 §4 places no type constraint on JWK members, so
`Jwks.keys` is `List<Map<String,Object>>` (`Jwks.java:31`) and `JwsVerify` reads only the members
it consumes, skipping an individual unusable JWK rather than rejecting the whole document
(`JwsVerify.java:244-267`).

**Every face that goes through the dispatcher reports the same code for the same cause.** gRPC,
`/agent/invoke` and MCP all read one table. Plain HTTP's *outer body* parse is the deliberate
exception: it happens in the netty handler before dispatch, never reaches `toClientError`, and
answers on the HTTP status line because that is what an HTTP client branches on.

| face | rejected value / invalid input | unexpected server failure |
| --- | --- | --- |
| gRPC | `INVALID_ARGUMENT` = **3** | `UNKNOWN` = **2** |
| plain HTTP POST (outer body) | **HTTP 400** | HTTP 500 |
| `/agent/invoke` | HTTP 200, `{"code":3,"message":"…"}` | `{"code":2,…}` |
| MCP `tools/call` | `isError:true`, `{"code":3,…}` | `{"code":2,…}` |

One table produces all of them: `UnaryMethod.toClientError` (`UnaryMethod.java:238-262`) is the
single exception→`Status` mapping, applied by both the gRPC path (`invoke`) and the HTTP path
(`invokeWeb`) that `/agent/invoke` and MCP dispatch through.

| exception | code |
| --- | --- |
| `InvocationTargetException` | unwrapped, then classified by its cause |
| `JsonDecodeException` (malformed body, or a value strict decoding rejects) | `INVALID_ARGUMENT` (3), sanitized description |
| `ValidationException` and any other `Status` carrier (all auth failures) | passed through unchanged — validation is `INVALID_ARGUMENT` (3) with `field(constraint)` detail |
| anything else | `UNKNOWN` (2), description `traceId,Class,message` |

There is no `INTERNAL` (13) row: an unexpected server-side exception has always been `UNKNOWN` on
the gRPC face, and the HTTP faces agree with it rather than inventing a different code.

> **BREAKING in 1.2.0 — `/agent/invoke` and MCP error codes.** Until 1.1.1 neither HTTP face applied
> the mapping (`/agent/invoke` hardcoded `13`; MCP fell to `Status.fromThrowable`'s `2`). **A
> dispatched request never answers `13` any more** — branch on `3` for "fix your input", `2` for
> "retryable/server-side". `13` is not extinct here: a failure OUTSIDE dispatch (building the
> request context, serializing the response) still answers `{"code":13,"message":"internal error"}`,
> meaning what it now means everywhere. MCP validation/auth codes are unchanged. Per-input old→new
> mapping: **changelog.md 1.2.0**.

**How much the `message` says depends on the face.** The *code* is uniform everywhere; the
*description* is graded — one classification, two disclosure levels — because an agent-facing
endpoint answers arbitrary callers while gRPC is service-to-service. The rule is answered from the
status code alone: **whose fault is it, and does naming the fault give anything away.**

| class | statuses | agent-face `message` | server log |
| --- | --- | --- | --- |
| **the caller's request was refused** | `INVALID_ARGUMENT`, `NOT_FOUND`, `ALREADY_EXISTS`, `FAILED_PRECONDITION`, `OUT_OF_RANGE` | **the description, verbatim** | bounded WARN — unless it carries a cause† |
| **the caller's request was refused, but the reason is sensitive** | `UNAUTHENTICATED`, `PERMISSION_DENIED`, `UNAVAILABLE`, `RESOURCE_EXHAUSTED`, `UNIMPLEMENTED`, `CANCELLED` | one fixed string per code (`unauthenticated`, `permission denied`, …) | bounded WARN — unless it carries a cause† |
| **we broke** | `INTERNAL`, `UNKNOWN`, `DATA_LOSS`, `ABORTED`, `DEADLINE_EXCEEDED`, **and anything not listed above** | `internal error`, plus `, trace=<traceparent>` when the caller sent one | **ERROR with the full stack** |

Read the two columns independently: `RESOURCE_EXHAUSTED` (and `UNIMPLEMENTED`, `CANCELLED`)
withholds its description yet is normally logged **without** a stack — anyone who can reach the
port can trigger it at will, and a stack per rejected request is a log-amplification lever.

**† A cause raises the log floor.** The code alone is too coarse, so the deciding signal is whether
anything actually threw: `Status.X.withCause(ex)` → **ERROR with the full stack**, whatever the
code; `Status.X.withDescription("daily quota")` with no cause → somebody *decided* to refuse →
**bounded WARN**. `INVALID_ARGUMENT` is the one code that never upgrades — its cause is the decode
failure itself, whose Jackson chain quotes the rejected value, so a cause there means caller data,
not a server fault.

**The default is the bottom row.** A code not named above — including any gRPC adds later — is
opaque to the client and fully logged; disclosure is opt-in. Two things are withheld because they
were actively harmful: **server-fault detail** (thrown class + raw message — SQL fragments,
connection strings, hostnames, file paths, the caller's own input echoed back) and **auth reasons**
(which distinguish "JWKS not ready" / "empty token" / "unknown kid" / "bad signature" / "expired",
several quoting the `kid`, `exp`, audience or client id — a credential-state oracle turning "is my
token rejected?" into "which part of my forgery was wrong?").

**What the server records, exactly:**

- The **status code and the failing exception's class name**, always, on every path.
- The **description** for every code EXCEPT `INVALID_ARGUMENT`, **stripped of control characters
  and capped at 200 characters** — this is where `JWKS not reachable at …` and `daily quota
  exceeded` survive for an operator. Cleaning is not cosmetic: auth descriptions interpolate
  values chosen by an **unauthenticated** caller (`kid`, rejected `exp`/`nbf`, client id), so
  logged raw they permit forged log lines with embedded newlines, volume inflation, or arbitrary
  text parked in retention. Audited: no token body, signature bytes or key material reaches any
  description.
- The **full cause chain** whenever the status carries a cause, or the code is one of ours
  (`INTERNAL`/`UNKNOWN`/`DATA_LOSS`/`ABORTED`/`DEADLINE_EXCEEDED`/anything unlisted).
- **Not logged at all: an `INVALID_ARGUMENT` description or cause** — a custom jakarta validator
  interpolating the rejected value, and the Jackson chain behind a decode failure, both put user
  input in the log. Code + exception class name identify what happened without it.

Everything logged is keyed by the same traceparent the client is handed for a server fault.

> **Service authors — three consequences.** (1) **Your refusal text reaches the client verbatim:**
> the message in a `NOT_FOUND`, `FAILED_PRECONDITION`, `ALREADY_EXISTS`, `OUT_OF_RANGE` or an
> `RpcResult.error(...)` is delivered unedited to whoever called you, agents over `/agent/invoke`
> and MCP included — no internal identifiers or hostnames, no SQL or stack fragments, no file
> paths, no user data or request echo. (2) **To let the caller see a withheld detail, use the
> business-code channel:** the framework cannot tell your "daily quota reached" from a
> `RESOURCE_EXHAUSTED` raised by an out-of-memory server, so it withholds both; return the
> specifics as a soft `RpcResult.error(code, msg)` (§3). (3) **Custom jakarta validators too:** a
> constraint message travels on `INVALID_ARGUMENT` and is passed through, so a validator
> interpolating the rejected value (`"'" + value + "' is not a valid IBAN"`) echoes it back to the
> caller and into `violations[]` on the MCP face. Built-in constraints are safe — krpc reads only
> `ConstraintViolation.getMessage()`, never `getInvalidValue()`.

The switch is evaluated on the decode path — during the first `parse` call(s), never in a class
initializer — then cached for the process (racy-but-deterministic: concurrent first callers may
each evaluate it, they cannot disagree). Reading it there is what keeps it live under GraalVM,
which runs class initializers at image build time. **Verified on a real native image** (quickstart,
GraalVM 25.0.3 / Quarkus 3.33.2): one binary, two runs — unset → `{"code":3,…}`,
`KRPC_JSON_STRICT=false` → `{"code":0,…}`, no rebuild. Caveat: a consumer whose own class
initializer calls `JsonUtils.parse` *and* is initialized at build time resolves the cache at build
time; no such path exists here.

---

## 5. @RpcService and service naming

`@RpcService` on the **interface** (`rpc-api/.../annotation/RpcService.java`). Attributes: `value`
(override name), `version`, `description`, `expireSeconds`. Name derivation
(`RefUtils.java:126-151`):
- strip leading `I`: `IDemoService` → `Demo`
- strip `Service`/`Rpc` suffix: `DemoService` → `Demo`, `FooRpc` → `Foo`
- full name = `appName/ServiceName` (e.g. `test-server/Demo`)
- **no dots** allowed in a service name

Call path is `app/Service/method`.

---

## 6. @UnsafeWeb — web exposure + hiding

`@UnsafeWeb` (TYPE-level, `rpc-api/.../annotation/UnsafeWeb.java`) marks a service **reachable
directly by browsers/frontend**. Without it the service name is prefixed with `-`
(`HIDDEN_SERVICE`, `RefUtils.java:124-150`) so the HTTP gateway keeps it service-to-service only.

```java
@UnsafeWeb                              // frontend-reachable
@UnsafeWeb(requireCredential = true)    // + JWT required for all methods
@UnsafeWeb.RequireCredential            // method-level JWT requirement
```
Credential precedence: method `@RequireCredential` > class `requireCredential` > default false
(`UnaryMethod.java:58-64`). When required, `ctx.checkCredential()` runs and throws (hard) on failure.

- **DO:** add `@UnsafeWeb` only to services the frontend must call directly.
- **DON'T:** add it to internal services (exposes them publicly). "Unsafe" is a reminder that
  **you** own the security hardening.

---

## 7. Validation

Uses **`jakarta.validation`** (not `javax.validation` — only the `jakarta` prefix is scanned,
`RefUtils.java:158-175`). A validator is attached only when a DTO has constraint annotations;
failures throw `INVALID_ARGUMENT` (hard, `ValidatorInvoke.java:31-44`).

```java
public class PagedQuery<Q> {                 // PagedQuery.java:28-36
    @NotNull @Min(1) Integer page;
    @Valid Q q;                              // @Valid cascades to nested DTOs
}
```
- **DO:** annotate with `jakarta.validation` constraints; add `@Valid` for nested cascade.

---

## 8. Authentication / context

Built-in auth **verifies** an incoming JWT against a remote **JWKS** endpoint (the `uid()` path,
once per authenticated request — `Es256Jwk.isValid`). krpc does **not issue** tokens while serving
RPCs: minting the ES256 JWT + publishing the matching EC JWKS at `rpc.server.jwks` is your **login
service's** job (krpc ships `Es256Jws`/`Es256Signature` for the signing side).

### 8.1 Require login on a service

Credential precedence: method `@RequireCredential` > class `requireCredential` > default false
(`UnaryMethod.java:59-65`). When required, `ctx.checkCredential()` runs before the method and
throws (hard) on failure (`UnaryMethod.java:197-199,227-229`).

```java
@UnsafeWeb(requireCredential = true)  // JWT required for every method
public interface AccountService { ... }
// per-method instead:  @UnsafeWeb interface … { @UnsafeWeb.RequireCredential AccountInfo me(); }
```

### 8.2 Configure JWKS

```properties
rpc.server.jwks=<your-jwks-base-url>
```
Read by both `rpc-server-spring` and `rpc-server-quarkus` (`InitJwsVerify.java:27,30`). If the URL
does not end in `.json`, krpc appends `.well-known/jwks.json` (`JwsVerify.java:32,62-64`) — so
configure the base URL, not the full document path. No JWKS set → auth check is skipped entirely
(`InitJwsVerify.java:56-58` / spring `:51-54`).

### 8.3 ES256 / EC keys only (silent-failure trap)

`loadJwks` loads **only keys with `kty == "EC"`** and ignores everything else with **no error**
(`JwsVerify.java:90-95`). RS256/RSA keys are silently dropped; tokens signed with them later fail
`kid not found` (`JwsVerify.java:127-129`). Verification is hard-wired to `SHA256withECDSA` /
`ES256` (`Es256Jwk.java:35,37,98-106`).

- **DON'T:** publish an RSA/RS256 JWKS — it fails silently at load, loudly at verify.
- **DO:** sign with ES256 and publish EC (P-256/384/521) keys (`Es256Jwk.java:32-34`).

### 8.4 Token extraction

`Authorization: Bearer <jwt>` is tried first; if absent, the cookie named `access-token`
(`JwsVerify.DEFAULT_COOKIE_NAME`, `JwsVerify.java:33`; extraction `CredentialVerify.java:34-59`;
selection `ServerContext.java:125-131`). Cookie name configurable via `rpc.server.jwsCookie`
(`InitJwsVerify.java:31,42` quarkus / `:28,39` spring).

### 8.5 Reading the user in an impl

```java
public AccountInfo me() {
    String userId = ServerContext.current().uid();   // JWT sub
    return load(userId);
}
```

- `ctx.uid()` — the JWT `sub`; throws (NPE) if not authenticated, so use only on
  `requireCredential` endpoints (`ServerContext.java:161-163`).
- `ctx.softUid()` — the `sub` or `null` when not logged in; never throws on a missing/invalid
  token (`ServerContext.java:142-155`).

### 8.6 Key rotation and revocation

A cache **hit** and an unknown `kid` (**miss**) both throttle their JWKS refetch through the SAME
window, at most once per **5 min** (`GAP_MILL`) — one constant, one clock (`JwsVerify.java`,
O3/O4). The **anti-amplification invariant this enforces: a random-kid spray can never push the
JWKS origin fetch rate above the baseline that steady-state legitimate traffic already causes.**
(An unknown-kid refetch is attacker-controlled — anyone can mint a JWT header carrying a random
kid, no valid credential required — so the previous separate 30 s miss-path window let a kid-spray
hit the origin at up to 10x the legitimate rate.)

- **Accepted tradeoff:** a freshly-rotated-in key's worst-case pickup latency moves 30 s → 5 min.
  Usually invisible — the hit-path refresh rebuilds the whole keyset, so any request against an
  existing still-valid kid pulls a newly-published key in as a side effect, and the first request
  carrying the new kid fetches immediately if it lands outside the window; only a sustained
  kid-spray pinning the window pushes a legitimate new-kid pickup to the full 5 min.
- Each successful fetch **rebuilds** the keyset (replace, not merge), so a **removed** key stops
  verifying within that serving without burning the window.
- A successful fetch returning **zero usable keys** (`{"keys":[]}`, null, or only non-EC) on an
  already-ready verifier = **full revocation**: the live keyset is replaced empty so every `kid`
  is rejected `PERMISSION_DENIED` (fail-closed), not dropped keeping the stale keyset (HARDEN-B1
  fix-round-1, O3). Before the first successful load, an empty/null-keys document keeps the
  verifier not-ready → `UNAVAILABLE`.

### 8.7 Production hardening — fail-closed by default

krpc is **fail-closed**: an unreachable/invalid JWKS URL never silently disables auth.

- **Default (`exitOnJwksError=false`):** verifier registered but **fail-closed** — until JWKS
  loads, every credential-required request is rejected gRPC **`UNAVAILABLE`** (`"JWKS not ready"`,
  distinct from a bad token's `UNAUTHENTICATED`/`PERMISSION_DENIED` so ops can tell "auth backend
  down" from "bad credential"). A background daemon retries with backoff (5 s → 60 s); on success
  auth goes live automatically (WARN).
- **`exitOnJwksError=true`:** startup **aborts loudly** — for deployments preferring crash-loop
  over serving while the auth trust root is unreachable.

```properties
# optional: abort startup instead of coming up fail-closed
rpc.server.exitOnJwksError=true
```

> **Behaviour change (HARDEN-B1):** pre-1.0.4 the default **fail-open**. That path is gone; there
> is no configuration that yields silent fail-open. A JWKS fetch is also bounded (5 s connect /
> 10 s request timeout, 1 MiB body cap) against a slow/oversized IdP.

### 8.8 Claim validation (exp / nbf / aud)

- **`exp`** is required and enforced — a token past `exp` is rejected
  `UNAUTHENTICATED`; a token **missing** `exp` is malformed → `UNAUTHENTICATED`.
- **`nbf`** is enforced when present, with a 60 s clock-skew allowance — a token whose
  `nbf` is more than 60 s in the future is rejected `UNAUTHENTICATED` (HARDEN-B1,
  behaviour change; tokens without `nbf` are unaffected).
- **`aud`** validation is **opt-in, default OFF** (behaviour unchanged for single-`aud`
  deployments). Per RFC 7519 the token's `aud` may be a single string **or** an array;
  both are accepted (a single-string `aud` no longer errors). Set a comma-separated
  allow-list to enable:

```properties
# optional: reject tokens whose aud does not intersect this list. empty = off.
rpc.server.jwsAudiences=api-gateway,internal
```

- Malformed tokens (bad structure / base64 / JSON / missing `exp` / missing `kid`) and
  non-canonical signatures (not exactly 64 raw bytes, incl. bare ASN.1/DER and
  degenerate all-zero-R / all-zero-S concat forms) are rejected `UNAUTHENTICATED` and
  logged at DEBUG only (no per-token stack-trace flood). No malformed/empty/failed path
  escapes as gRPC `UNKNOWN` (HARDEN-B1 fix-round-1).

---

## 9. Other authoring annotations

- **`@Doc`** (`Doc.java`) — TYPE/FIELD/METHOD docs fed into generated clients;
  `hidden=true` omits from clients; nested `@ErrorCode` documents business codes.
- **`@Cached`** (`Cached.java`) — client-side cache by `expireSeconds` (method >
  `@RpcService.expireSeconds` > manager default); **only `code==OK` results are
  cached** (`MethodCallProxyHandler.java:148`).

---

## 10. Service implementation

Implement the interface (optionally via an `AbstractXxxService` base for shared defaults).
Container wiring: **Quarkus** `@ApplicationScoped @Startup` (`DemoServiceImpl.java:19-22`);
**Spring** see `rpc-server-spring`.

The gRPC/Netty server executor runs on **virtual threads** (one named virtual thread per RPC,
`rpc-server/.../exe/ThreadPool.java`; JDK 21 / ADR-0002) — handlers may block on IO freely; don't
add your own bounded RPC thread pool.

---

## 11. Wire / JSON / tracing (mostly automatic)

- Wire envelope `OutputProto` uses single-letter fields `c`(code)/`m`(msg)/`bs`(bytes); null
  `msg`/`data` are not written (`ServerResult.java:30-38`).
- Trace propagation is automatic, **W3C Trace Context** (ADR-0003): the server reads inbound
  `traceparent` into MDC (`traceId`/`spanId` for the log pattern), carries `tracestate` +
  `x-request-id`; the client forwards `traceparent` opaquely (`TraceMeta.java`,
  `ServerContext.java`, `MethodCallProxyHandler.java`, `PropagateTraceCall.java`). It propagates
  context, does not start spans. B3 (`x-b3-*`) is no longer emitted/read — a wire change vs 1.0.0;
  sibling clients must adopt W3C for cross-service continuity.

---

## 12. Build, test, release

Config keys below are **contract** — consumers set them. Operational how-to (publish steps,
rollback runbook) lives in `gradle/publish-central.sh`, `AGENTS.md` "Repo Facts" and
`skills/krpc/references/operations.md §16.5`; only the hazards are restated here.

```bash
./gradlew clean build -x :test-server-spring:test   # tests included; needs Docker (DB-backed
                                 # tests start their own MySQL via Testcontainers). The
                                 # exclusion is a known-red Spring module, see AGENTS.md
./gradlew clean build -x test    # only for environments without Docker
./gradlew allDeps
```
JDK 21 baseline; pinned Gradle wrapper 9.6.0. When you skip tests, say so and list what was not
validated. Native (container build, Mandrel/JDK 25):
```bash
./gradlew :test-server:build -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  -Dquarkus.package.jar.enabled=false -x test
```

Release to Maven Central (group `tech.krpc`; credentials in `~/.gradle/gradle.properties`, never
in the repo) — steps and flags in `gradle/publish-central.sh`:
```bash
GRADLE_CMD=./gradlew gradle/publish-central.sh upload         # build+sign+upload, wait VALIDATED (reversible)
GRADLE_CMD=./gradlew gradle/publish-central.sh publish --yes   # IRREVERSIBLE — cannot be deleted/overwritten
```
Set `version` + `changelog.md` first; `ext-rpc-gen` (group `tech.krpc.ext`) releases on its own
cycle, not in the krpc bundle. Tag `vX.Y.Z` and cut the GitHub release after Central
publish succeeds.

### 12.1 Server limits / hardening config

```properties
# Max concurrent in-flight calls per HTTP/2 connection. Default 2000; 0 = unlimited.
rpc.server.maxConcurrentCallsPerConnection=2000
```
App-layer defence-in-depth against HTTP/2 concurrent-stream flooding (CVE-2026-47244). Read by
`rpc-server-quarkus` (`RpcServiceExpose`, env `RPC_SERVER_MAXCONCURRENTCALLSPERCONNECTION`) and
`rpc-server-spring` (`RpcServiceExposer`, relaxed binding —
`rpc.server.max-concurrent-calls-per-connection` / env also work); applied on the Netty gRPC
`ServerBuilder` in `RpcServerBuilder.init()`. The value is advertised as HTTP/2
`SETTINGS_MAX_CONCURRENT_STREAMS`, so a client exceeding it on a **single channel** is
**back-pressure queued** — excess streams wait client-side, they are **not** failed. `0` is the
only unlimited value (pre-1.0.4 behaviour); a **negative value fails fast** as a config error.
Non-Netty gRPC providers (none ship by default) ignore the cap with a warning.

### 12.2 MCP bridge (agent tools over `POST /mcp`)

ADR-0004 P1: a hand-written [Model Context Protocol](https://modelcontextprotocol.io) bridge
(spec `2026-07-28`, JSON-RPC 2.0 over Streamable HTTP) on the same netty HTTP host as
`/agent/*` (`http.port`, default `8080`). No third-party SDK, no new module/artifact.

```properties
# Default OFF = byte-level zero new surface (the /mcp path is not even registered).
rpc.server.mcp.enabled=true
```
Env: `KRPC_MCP=true` or the SmallRye mapping `RPC_SERVER_MCP_ENABLED` (`rpc-server-quarkus`,
`McpHandler`).

- **Default OFF**; tools = the `@UnsafeWeb(agentTool=true)` subset only (`@UnsafeWeb` alone does
  **not** create a tool).
- **`tools/call` runs the identical dispatch as `/agent/invoke`** — credential check **not** bypassed.
- Stateless since 1.1.0 (no session id, one self-contained JSON object per POST, no SSE), so the
  07-28 stateless line is an additive alignment. SSE/resumability, sessions, MRTR and
  subscriptions are **design-exempt, deliberately not implemented** — do not "fix" without an ADR.

**Full behavior is normative and lives in `skills/krpc/references/mcp-bridge.md`**: dual version
track + `params._meta` version position, version enforcement and method×version binding,
`server/discover`, `Mcp-Method`/`Mcp-Name` header-mismatch rules, JSON-RPC id discipline,
JSON-Schema derivation, dispatch/error mapping, `GET`→405, verification transcripts.

### 12.3 Client limits / hardening config

```properties
# Default outbound call deadline (ms) when the caller sets none. Default 30000; 0 = unlimited.
rpc.client.defaultDeadlineMillis=30000
```
**⚠️ Behaviour change (HARDEN-B2, O2):** a deadline-less outbound call previously used
`CallOptions.DEFAULT` (no deadline) so a hung upstream blocked the calling virtual thread forever;
now it gets this default via one authority (`ClientDeadline.apply`, `rpc-client`) shared by the
proxy path (sync + async, `MethodCallProxyHandler`), `GeneralizeClient`, and rpcurl (Spring binds
it in `RpcClientAutoConfigure.afterPropertiesSet` — the **single** point). **A legitimate slow call
is cut with `DEADLINE_EXCEEDED`** — raise the value or set an explicit per-call deadline, which
always wins (`ClientContext.withCallOptions(CallOptions.DEFAULT.withDeadlineAfter(...), ...)` or a
filter setting `CallOptions.getDeadline()`). `0`/negative = unlimited = pre-1.0.4.

Other client hardening in the same batch carries no config key (thread-safe `@Cached`
`SimpleLRUCache`, `dataCase`-tagged cache keys, cloned `byte[]` on both cache edges,
`RpcClientFactory` as a `destroyMethod="close"` bean, portless config URL → protocol default
port) — see **changelog.md**.

### 12.4 HTTP face — error model, threading & idle limits (HARDEN-B4)

The `http-server` netty host serving `/agent/*` and `/mcp` (`AbstractHttpHandler`, `HttpServer`).

**Error model.** Every error is a uniform JSON envelope `{"code":<httpStatus>,"message":<text>}`
with matching `content-type: application/json`:

| condition | status |
|---|---|
| malformed JSON body | `400` (neutral `"malformed JSON…"`; Jackson internals only in the log) |
| empty body to a validating endpoint | `400` `"request body is required"` |
| bean-validation failure | `400` (field path + constraint; **never** the rejected value — PII stays in the log) |
| request body > 1 MiB | `413` (netty `HttpObjectAggregator`) |
| unknown path | `404` |
| handler internal error | `500` (status reason phrase only — **never** `ex.getMessage()`) |

A malformed body is a 400 JSON with the connection kept open (no bare `ctx.close()` reset) — the
HTTP analogue of the gRPC `JsonDecodeException`→`INVALID_ARGUMENT` mapping; the agent/MCP surface
never discloses an internal reason.

**Threading and ordering.** `channelRead0` dispatches `handler.handle()` to a per-request **virtual
thread** so blocking never runs on the netty NIO eventLoop; the response write is scheduled back on
the channel's eventLoop. Backpressure: `setAutoRead(false)` in flight bounds blocking work to ≤1 per
connection, re-armed when the queue drains. HTTP/1.1 needs in-order responses and `handle()` runs
off the eventLoop, so each connection carries a **FIFO queue** (`ConnState`, a channel attribute
touched only on the eventLoop): every request (handler and error/404 alike) is enqueued and the
next is dispatched only after the current response is written — strict one-in/one-out.

**Idle / slow-loris.** An `IdleStateHandler` (reader-idle `HttpServer.READ_IDLE_SECONDS` = 60s)
closes a connection sending no inbound bytes in that window. `HttpServer.start()` shuts down both
`NioEventLoopGroup`s if bind fails.

### 12.5 Deployment environment tag — `APP_ENV`

`APP_ENV` names the **deployment environment** for display and telemetry only
(`rpc-common/.../util/EnvUtils.java`). The parse is case-insensitive and
whitespace-trimmed:

| value | aliases | `AppEnv` |
|---|---|---|
| `dev` | `develop`, `development` | `DEV` |
| `test` | — | `TEST` |
| `staging` | `stage`, `pre` | `STAGING` |
| `prod` | `production` | `PROD` |

- **Unset → `DEV`** (local-dev default; only consumer today is the startup banner). **Unknown →
  warn once + `PROD`** (fail-closed, never throws).

- **Display / telemetry only — never a behaviour switch.** A behavioural toggle gets its **own**
  env flag (template `KRPC_MCP` / `rpc.server.mcp.enabled`, §12.2); if a branch is ever keyed off
  the environment, the unset default flips `DEV`→`PROD`.
- **Two axes:** the `APP_ENV` **label** is independent of the **Quarkus runtime profile**
  (`dev`/`test`/`prod`) — **staging runs the `prod` profile** while carrying `APP_ENV=staging`;
  don't wire them to the same value.
- **Telemetry:** emit as OTel resource attr `deployment.environment.name`, **lowercase**
  (`dev`/`test`/`staging`/`prod`).

### 12.6 Persistence & transaction discipline

The **ecosystem default is weak transaction, throughput first**, implemented
*safe-by-construction* by every data-access extension. Under the VT runtime (one VT per RPC —
[§10](#10-service-implementation)) the pooled connection is the scarce resource; most RPC methods
are reads or single-statement writes needing no cross-statement atomicity, so the default borrows a
connection per operation and returns it **immediately** (never parked on a request/session/thread)
— no tuning flag. A multi-write invariant opts in explicitly to an `@Transactional`/JTA scope,
binding the connection until it ends.

| your method does | posture |
| --- | --- |
| a read, or one single-statement write | default auto-commit — do nothing |
| **≥ 2 writes that must all commit or all roll back** | explicit `@Transactional` / JTA |

- **DON'T:** ship a **silent multi-write** with no transaction — each statement commits on its
  own, so a mid-sequence failure leaves earlier writes durably applied (partial state, no rollback).
- **DON'T:** cache a borrowed connection / `SqlSession` in a field or `ThreadLocal` — that pins the
  scarce resource and defeats the return-immediately default.

The data-access extensions (`ext-mybatis` ≥ 1.0.2) are safe-by-construction here (the default path
cannot leak or pin a connection); per-extension config is in each extension's README, rationale =
ecosystem **ADR-0002 (weak-transaction default)**.

---

## 13. Native image (GraalVM)

Native-image builds are consumer-proven (2026-06, 8 downstream Quarkus 3.33.2 services,
boot-to-ready 0.028s native). **Full recipes — io.grpc/Quarkus-LTS version matrix, `ext-rpc`
server-side support, io_uring eval, reflection coverage → `skills/krpc/references/native-image.md`**
(`[gap → NATIVE-00x]` = framework defects to delete when the roadmap item lands; the `§13.x`
refs below point into that file). Day-1 checklist for a consumer service:

- [ ] **krpc ≤1.0.2 only:** `io.grpc:*` forced to the Quarkus BOM version (root build) — §13.1. (krpc >1.0.2 aligned; skip.)
- [ ] `ext-rpc` ≥ 1.0.2 on the build (server-side native; ≤1.0.1 needs the legacy trio) — §13.2.
- [ ] Builder image matches Quarkus/JDK line; `package.jar.enabled=false` — §13.3.
- [ ] Per-service `--initialize-at-run-time` for static-heap violations — §13.3.
- [ ] Datasource config present at runtime (SIGSEGV otherwise) — §13.3.
- [ ] `ext-rpc` / `ext-mybatis` extensions on the build (DTO reflection) — §13.5.

**Metadata layout — `reachability-metadata.json`, auto-detected.** Every published krpc jar ships
its native metadata under the standard `META-INF/native-image/tech.krpc/<artifactId>/`, picked up
**by location** (krpc passes no deprecated `-H:ReflectionConfigurationResources` /
`-H:DynamicProxyConfigurationResources`). Each directory carries the combined
`reachability-metadata.json` (GraalVM/Mandrel 24+) **and** the legacy `reflect-config.json` /
`proxy-config.json`, because 21–23 ignore the combined file outright — dropping the legacy pair
before the JDK 21 native floor retires would silently un-register every type. Both are read;
registration is a union. Consumers add nothing — §13.6.

**Known issue — Caffeine + native reflection** (field experience, 2026-07-19). A krpc native
service using Caffeine's **bounded** cache path (`weakKeys`/`softValues`/`expireAfter*`/
`maximumSize`/…) hits a dynamic class resolution: Caffeine picks a pre-generated internal class by
its *feature-encoded* name (`SSMSW`/`PSWMS`-style, **varying** with the feature combination). Green
on the JVM, `ClassNotFoundException` at native runtime. Remedy: register the concrete generated
class(es) for reflection (`@RegisterForReflection(classNames = …)` or reflect-config) and
**re-verify whenever the feature combination changes** — adding/removing features changes the
encoded name (numeric capacity/duration values do not).

---

## 14. Contract evolution

The contract is the `*-api` module (`@RpcService` interfaces + DTOs; NS-1 — the interface *is* the
contract). Evolving it safely is publish-time discipline, not a runtime feature.

**On the wire, the field layout *is* the contract.** Polyglot clients do not key on any version
string — they encode the `InputProto`/`OutputProto` field numbers directly
(`InputProto{e=1,utf8=2,bs=3}`, `OutputProto{code=1, data oneof{msg=2,utf8=3,bs=4}}`;
`RpcConstants.VERSION` rides as `ApiMeta.sdkVersion` metadata only). Those field numbers are
effectively frozen: reusing or renumbering one is a wire break regardless of any version bump
(verified across the Rust/TS/Python/Dart clients, 2026-07).

### 14.1 Version policy — the major stays `1`

`version` in `gradle.properties:5` (group `tech.krpc`); line `1.MINOR.PATCH`. Mirrors
`docs/support-policy.md` (superseded minor → security-only); today = **author discipline**, not
machine-enforced (§14.2).

| bump | contract change | old clients | deploy |
| --- | --- | --- | --- |
| **major** (`1`) | **frozen, never increments** | — | wire envelope stable (NS-2: wire-compat originates in krpc) |
| **minor** | **breaking** ("consumers must move") | may fail | front+back same window (§14.3) |
| **patch** | **compatible** (additive/fix) | keep working | stagger OK |

### 14.2 `*-api` publish gate — japicmp (recommended, NOT wired here)

> **External recommendation** — grep `japicmp` = 0 hits; a reference impl runs in a downstream
> consumer CI, not anchorable here. Manual REQUIRED step until krpc wires it.

Run a binary/source compat check (`japicmp`) of the candidate `*-api` jar vs the last release,
in the publish pipeline:

| candidate change | on patch bump | on minor bump |
| --- | --- | --- |
| incompatible (removed/renamed method, changed DTO field type, narrowed return) | **FAIL** | pass |
| additive-only (new method, new optional field) | pass | pass |

### 14.3 Deploy-window rule — breaking changes deploy together

> External recommendation (field report; not a krpc-code fact).

A **breaking** change (minor, §14.1) MUST deploy **front and back in the same window**;
additive/compatible (patch) MAY stagger. The wire envelope is frozen (major `1`, NS-2), so a
removed/renamed method or changed DTO shape has **no** compat bridge — cut over atomically.
(Real staging outage: a one-day skew put the two sides on different contracts, calls failed.)

---

## 15. Consumer guide (calling a KRPC service)

For agents/humans **calling** a running service (authoring is §1–§11).

> **Standing instruction — method names are contract, not guessable.** The rpcurl/gRPC
> path has no runtime schema handshake (external gap **RPCURL-001**); HTTP `/agent/discover`
> *does* introspect (§12.2 / `docs/agent-guide.md`). Until rpcurl introspection lands,
> **grep the `*-api` interface before calling** — a wrong method/field guess resolves to
> `UNIMPLEMENTED`/`code:5`, not a hint (§15.4).

### 15.1 Auth — two independent token lanes

A JWT rides one of **two lanes**, both feeding the same verifier (§8.4): `Authorization: Bearer`
first, then cookie `access-token` (`JwsVerify.java:49` `DEFAULT_COOKIE_NAME`; selection
`ServerContext.java:137-144`).

| lane | wire | rpcurl | when |
| --- | --- | --- | --- |
| **Business** | `Cookie: access-token=<jwt>` | `-c "access-token=$TOK"` — value verbatim on `cookie`, use the `name=value` equals form (rpcurl CLI, `krpc-crates/rpcurl`) | browser/frontend caller whose login set the cookie |
| **Framework** | `authorization: Bearer <jwt>` | `-t "$TOK"` — prepends `Bearer ` (rpcurl CLI, `krpc-crates/rpcurl`) | s2s / tooling with the raw JWT |

- **No `KRPC_TOKEN` env var** — the framework lane is the `-t`/`--token` flag only.
- The agent HTTP surface (`/agent/invoke`, `/mcp`) forwards only `Authorization`, not `Cookie`
  → cookie lane unavailable there; send a bearer token (`docs/agent-guide.md` "Exposure model").

### 15.2 URL construction

Call path `{app}/{Service}/{method}` (§5); gateway URL `{gateway}/{app}/{Service}/{method}`, each
piece from `RefUtils.rpcServiceName` (`RefUtils.java:173-198`, derivation `:179-196`): strip a
leading `I`-before-uppercase and a trailing `Service`/`Rpc`, **no dots**.

| service declaration | published name | gateway path | reachable |
| --- | --- | --- | --- |
| `@UnsafeWeb IDemoService` | `Demo` (`I` stripped) | `{gateway}/{app}/Demo/{method}` | frontend + s2s |
| `@UnsafeWeb DemoService` | `Demo` (`Service` stripped) | `{gateway}/{app}/Demo/{method}` | frontend + s2s |
| `@UnsafeWeb FooRpc` | `Foo` (`Rpc` stripped) | `{gateway}/{app}/Foo/{method}` | frontend + s2s |
| `DemoService` (no `@UnsafeWeb`) | `-{app}/Demo` | *not web-served*; gRPC path `-{app}/Demo/{method}` (prepend `-` in the rpcurl URL) | **s2s only** |

For a non-`@UnsafeWeb` service `RefUtils` prepends `HIDDEN_SERVICE` (`-`) to the **whole**
`{app}/{Service}` (`RefUtils.java:192-196`); the `-` keeps a hidden service off the web gateway.
The rpcurl CLI (`krpc-crates/rpcurl`, Rust) takes the full URL directly — prepend the `-`
yourself; it has no `--no-web` flag. On the plain-HTTP agent surface the name is used
**app-relative** (`{"service":"Demo","method":"..."}`), lookup = literal `"Service/method"` key
(`WebMethodRegistry.java:36-40`) — hidden/unknown → `null` → `code:5` NOT_FOUND.

### 15.3 Field-format conventions (quick table)

**DTO-authoring conventions** — how to *type* a field for clean polyglot round-trips, **not**
serializer behaviour (`JsonUtils.java` guarantees `NON_NULL` output (`:76`), tolerates unknown
input fields `FAIL_ON_UNKNOWN_PROPERTIES=false` (`:77`), and since 1.2.0 REJECTS a
number/boolean sent into a `String` target unless the `KRPC_JSON_STRICT` kill switch is
pulled, §4). Combine with §4 (boxed scalars, `NON_NULL`).

| logical type | represent as | why (convention, not serializer-enforced) |
| --- | --- | --- |
| date | `String`, `YYYY-MM-DD` | `java.time` is NOT ISO-configured — `JsonUtils` adds `JavaTimeModule` only if on classpath (`JsonUtils.java:79-88`), never disables `WRITE_DATES_AS_TIMESTAMPS`, so a raw `LocalDate`/`OffsetDateTime` serializes numeric (test DTOs keep it off the wire, `test-api/.../dto/TimeResult.java:23-25` commented) |
| datetime | `String`, ISO-8601 with zone | same as date |
| money | `Long`, integer **cents** (`1999`=19.99) | avoids float rounding — never `Float`/`Double` (contrast tolerant geo `Float`, `Book.java:10-17`) |
| large id | `String` (`"7300000000000000001"`) | avoids the JSON/JS `2^53` cliff for 64-bit ids (`test-api/.../dto/Img.java:26` `String id`); a boxed `Long` risks silent precision loss in JS/TS/Dart |

### 15.4 Error-code behavior — how to branch

Branch on the failure *shape* (gRPC/rpcurl path):

| failure | code | carries | caller action |
| --- | --- | --- | --- |
| **Jakarta validation** | `INVALID_ARGUMENT` | field detail `Dto : field=value(constraint)` (`ValidatorInvoke.java:35-41`) | self-correct the named field |
| **Malformed JSON body** | `INVALID_ARGUMENT` | **no** field — `<traceId>,malformed JSON request body` (`UnaryMethod.java:243-249`) | fix the request JSON |
| **Unauth / forbidden** | `UNAUTHENTICATED` / `PERMISSION_DENIED` | terse auth reason, no field hint: `"requireCredential but empty token"` (`JwsVerify.java:401`), `"Token expired at: …"` (`:485`), `"kid not found or expired"` (`:448`), `"invalid signature !"` (`:454`) | fix the token — can't infer a valid call (gap **RPCURL-001**) |
| **Unimplemented / not found** | `UNIMPLEMENTED` (rpcurl) / `code:5` (agent HTTP: `{"code":5,"message":"Service/method not found"}`, `WebMethodRegistry.java:36-40`) | bare "method not found" | wrong/hidden method/service (hidden ≡ missing, by design) |

`INVALID_ARGUMENT` spans two shapes (validation = field-level; malformed JSON = bare). On the
HTTP face the rejected *value* is stripped even for validation errors (field path + constraint
only, PII in the log — §12.4).

### 15.5 Soft errors are a consumer UX obligation

> Consumer field case (2026-07-19); external doctrine, not a krpc-code fact.

A service returning a "processing / will converge later" soft error (UNAVAILABLE-class) obliges the
**consumer** to translate it into a user-visible **intermediate state** + **idempotent re-entry** —
never toast the raw error while leaving the UI state unchanged (a user who sees a bare error
retries; if the request already took effect that is a double-submit hazard). Reference shape: the
action moves to an intermediate status (e.g. an order → `refunding`), the soft error maps to
**success semantics** for the caller, retries are idempotent, and a **reconcile worker** converges
the final state.

### 15.6 Consuming releases & smoke example → reference

`-rc` consumption routes (mavenLocal / vendored file-repo / Nexus — never shadows a Central
GA) and the LH end-to-end smoke script → `skills/krpc/references/operations.md`.

---

## 16. Operations facts

Ops/runtime facts that bite in production. Quick-ref below; observability contract,
zero-trace fault tree, no-span inference, and release/rollback runbook →
`skills/krpc/references/operations.md`.

### 16.1 Config: build-time vs runtime boundary

A native image bakes **build-time-consumed** config into the binary; **runtime-consumed** config
(`@ConfigProperty`/env, read at bean init) flips on the *same* binary — no rebuild.

- **General rule:** a value consumed in a *build* step (native static init / an Arc build item) is
  frozen into the image; a runtime env override does nothing.
- **Client routing URLs (`rpc.client.*.url`) — NOT verifiable here.** The build-bake concern was
  recorded as **EXTRPC-URL-001** (`benchmark/RESULTS.md:24-29` is that historical bake, **not** a
  fix); the runtime-URL fix lives in the `ext-rpc` repo (out of tree). No production
  `rpc.client.*.url`, Quarkus RUN_TIME root, or native override test exists here, so "routing keys
  are runtime-overridable" is **unverified here**.
- **Verify (proven for `rpc.server.defaultExecutor` only):** `RPC_SERVER_DEFAULTEXECUTOR` flips the
  VT-vs-pool branch on one native binary, seen in the startup log (`benchmark/RESULTS.md:93-104`).
  Apply the same env-flip to any runtime key.

### 16.2 Port authority

Three server faces, three ports — do not conflate.

| face | config key | default | speaks |
| --- | --- | --- | --- |
| gRPC gateway | `rpc.server.port` | **50051** (`RpcConstants.java:33` `DEFAULT_PORT`) | HTTP/2 gRPC (use `rpcurl`) |
| krpc HTTP (agent/MCP) | `http.port` | **8080** (`HttpHandlerExpose.java:36`, `HttpServer.java:32`) | plain HTTP/1.1 JSON (`/agent/*`, `/mcp`) |
| Quarkus REST | `quarkus.http.port` | **8080** (Quarkus default) | consumer's own Vert.x/REST |

All services keep the **same** default gRPC port (**50051**) — distinguish instances by K8s
**service name**, not by hand-assigning an incrementing port per app; the `50051`–`50058` per-app
style is single-machine legacy that new projects must not copy.

The krpc HTTP face is **its own netty server** (`new HttpServer(this, port)`,
`HttpHandlerExpose.java:86`), separate from Quarkus's Vert.x HTTP — both default to **8080**, so a
Quarkus consumer serving REST **collides**. A probe/`curl /agent/discover` at `:8080` hitting
Quarkus REST (404 / wrong body / bind conflict) is a port-identity mixup, not a broken endpoint —
reassign one face (e.g. `http.port` → `8088`). gRPC (50051) never collides.

---

## 17. Quick checklist for a new service

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
