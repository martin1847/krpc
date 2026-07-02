# Agent Guide — discovering & calling KRPC services over HTTP

How an AI agent (or a human wiring one up) discovers a KRPC service's schema and
calls a method over plain HTTP/1.1 JSON — no gRPC stack, no compiled client.

This is the **P0 HTTP `discover → invoke` loop** from
[ADR-0004](decisions/ADR-0004-agent-friendly-introspection.md) (roadmap AGENT-001).
It exposes KRPC's existing runtime self-description (`RpcMetaService.listApis()` /
`ApiMeta`) and generic invoke (`GeneralizeClient`) through two HTTP endpoints.

> Authoring rules (method contract, `RpcResult`, errors, DTOs, `@UnsafeWeb`, auth)
> live in [`SPEC.md`](../SPEC.md). The portable, tool-agnostic quick-reference is
> the `krpc` skill (`skills/krpc/SKILL.md`). This page is the runtime call surface.

## Endpoints

| method | path | purpose |
| --- | --- | --- |
| `GET`  | `/agent/discover` | web-only `ApiMeta` as JSON (services + method signatures + DTO type trees + `@Doc` + validation constraints) |
| `POST` | `/agent/invoke`   | resolve `Service/method`, forward JSON input verbatim, dispatch through the same credential + filter path as gRPC |

**Port.** These ride the plain-HTTP server on `http.port` (**default `8080`**),
which is **separate** from the gRPC gateway port `rpc.server.port` (default
`50051`). The gRPC gateway speaks HTTP/2 framing (use `rpcurl` there); the agent
endpoints speak plain HTTP/1.1 JSON that any HTTP client can call.

## Exposure model (honest version)

- **The agent surface is a filtered view of the web surface.** `/agent/discover`
  and `/agent/invoke` see exactly the `@UnsafeWeb` services — the same set the
  browser/frontend gateway can reach. Services without `@UnsafeWeb` keep their `-`
  (hidden) prefix and **never** appear in discovery and **cannot** be invoked
  (unknown and hidden resolve identically to a not-found, with no internal
  disclosure).
- **There is no narrower "agent tool" subset yet.** ADR-0004 specifies
  `@UnsafeWeb(agentTool=true)` as the opt-in that would make the agent surface a
  deliberate *subset* of the web surface — but that is a **P1 design that is
  accepted, not yet built**. Today (P0) the agent surface equals the web surface.
  Do not assume an `agentTool` gate exists.
- **Credential is not bypassed relative to gRPC.** `/agent/invoke` forwards the
  `Authorization: Bearer <jwt>` header into the **same credential check as a normal
  gRPC call**, so an `@UnsafeWeb(requireCredential=true)` service is checked no
  differently on this path. Note the agent path forwards **only** `Authorization`
  (plus client-id and `traceparent`) — it does **not** forward a `Cookie` header, so
  the cookie-based credential fallback that works on the web/gRPC path is **not
  available on the agent surface** in the current implementation; send the JWT as a
  bearer token. Whether the check actually **rejects** an unauthenticated call
  depends on auth being configured: it enforces only when a credential verifier is
  registered — i.e. `rpc.server.jwks` is set and loads. With JWKS missing or
  unloadable and `exitOnJwksError` off, the check **silently skips** (SPEC §8.7).
  Configure JWKS and set `exitOnJwksError=true` in prod; this path adds no security
  guarantee beyond what that configuration gives.
- **Auth and rate-limiting are the gateway's responsibility, not KRPC core.**
  `/agent/discover` exposes the full schema of every `@UnsafeWeb` service to any
  caller, and `/agent/invoke` reaches every `requireCredential=false` `@UnsafeWeb`
  service. A deployment MUST place both paths behind a gateway that enforces auth
  and rate limits (ADR-0004).

## Request / response shape

`POST /agent/invoke` body:

```json
{ "service": "<Service>", "method": "<method>", "input": { /* the one DTO, or omit */ } }
```

- `service` / `method` are **app-relative** — the `app/` prefix is stripped. A
  service published at `quickstart/Hello/hello` is invoked as
  `{"service":"Hello","method":"hello"}`.
- `input` is the method's single request DTO as free-form JSON, forwarded verbatim
  (the target method does its own deserialization + validation).
- Responses mirror `RpcResult`: `{"code":0,"data":{…}}` on success;
  `{"code":<n>,"message":"…"}` on error. **Errors ride the `code` field in the
  body, not the HTTP status line** — the transport emits only `200` (handled),
  `404` (unknown path), or `500` (uncaught). Unknown/hidden service → `code:5`
  (gRPC `NOT_FOUND`).

## A real discover → invoke transcript

Run against the [`examples/quickstart`](../examples/quickstart/) service
(`@UnsafeWeb` `HelloService`, no DB, no JWT).

> **Prerequisite (known P0 limitation).** The two handler beans are discovered
> reflectively, so Quarkus Arc's default unused-bean removal strips them and the
> HTTP server logs `Skip HTTP Server , no Handlers found.` — the endpoints are then
> absent. Until this is addressed, build the app with unused-bean removal off so
> the handlers are retained (JVM mode only; native reflection-config for these
> handlers is also not added yet):
>
> ```bash
> gradle :examples:quickstart:quarkusBuild -x test -Dquarkus.arc.remove-unused-beans=none
> java -jar examples/quickstart/build/quarkus-app/quarkus-run.jar
> ```
>
> Startup then logs the surface coming up:
>
> ```text
> HttpHandlerExpose  GET [/agent/discover]
> HttpHandlerExpose  POST [/agent/invoke]
> HttpHandlerExpose  ***** 【 DEV 】 HTTP Server 2 endpoints  on 8080
> RpcServiceExpose   ***** 【 DEV 】 RpcServer expose 1 services on 50051
> ```

### 1. Discover

```bash
curl http://127.0.0.1:8080/agent/discover
```

```json
{
  "app": "quickstart",
  "apis": [
    {
      "name": "Hello",
      "methods": [
        {
          "name": "hello",
          "arg": {
            "rawType": {
              "name": "HelloRequest", "input": true, "parameterized": false,
              "fields": [
                { "name": "name",
                  "type": { "rawType": { "name": "String", "input": true, "parameterized": false } },
                  "annotations": [ { "name": "NotBlank", "properties": {} } ] }
              ]
            }
          },
          "res": {
            "rawType": {
              "name": "HelloReply", "input": false, "parameterized": false,
              "fields": [
                { "name": "message",   "type": { "rawType": { "name": "String", "input": true,  "parameterized": false } } },
                { "name": "timestamp", "type": { "rawType": { "name": "Long",   "input": false, "parameterized": false } } }
              ]
            }
          },
          "annotations": [
            { "name": "Doc", "properties": { "hidden": false, "value": "Returns a greeting for the given name." } }
          ]
        }
      ],
      "description": "KRPC quickstart demo service",
      "web": true
    }
  ],
  "dtos": [ /* HelloRequest, HelloReply, String, Long — the DTO closure */ ],
  "sdkVersion": "1.0.0",
  "vendor": "java",
  "buildVersion": "null-2026-07-03 02:00"
}
```

The agent reads: service `Hello`, method `hello`, input `HelloRequest{ name:String
@NotBlank }`, output `HelloReply{ message:String, timestamp:Long }`, plus the
`@Doc` description. That is enough to construct a valid call.

### 2. Invoke

```bash
curl -X POST http://127.0.0.1:8080/agent/invoke \
  -H 'Content-Type: application/json' \
  -d '{"service":"Hello","method":"hello","input":{"name":"krpc"}}'
```

```json
{"code":0,"data":{"message":"Hello, krpc!","timestamp":1783015301792}}
```

### 3. Unknown or hidden service → not found

```bash
curl -X POST http://127.0.0.1:8080/agent/invoke \
  -H 'Content-Type: application/json' \
  -d '{"service":"Nope","method":"x","input":{}}'
```

```json
{"code":5,"message":"Nope/x not found"}
```

A hidden (non-`@UnsafeWeb`) service returns the identical `code:5` response — the
agent cannot distinguish "does not exist" from "exists but hidden", by design.

## What is not here (P1, not built)

- **Native MCP server.** ADR-0004 P1 is a runtime MCP module generated from live
  `ApiMeta`, Streamable HTTP, feature switch default OFF. **Not started.**
- **`@UnsafeWeb(agentTool=true)` opt-in.** The agent-tool subset gate above.
- **In-core auth / rate limiting.** Deferred to the gateway (ADR-0004).

See [ADR-0004](decisions/ADR-0004-agent-friendly-introspection.md) and the
[active roadmap](roadmap/active-roadmap.md) AGENT-001 entry for phasing.
