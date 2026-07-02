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
- **The `agentTool` subset is the MCP surface (now built, P1).**
  `@UnsafeWeb(agentTool=true)` opts a service into the MCP tool surface (`POST /mcp`,
  see [MCP bridge](#mcp-bridge-post-mcp) below) — a **deliberate subset** of the web
  surface. `@UnsafeWeb` alone does **not** create an MCP tool. The `/agent/discover`
  and `/agent/invoke` views here are **unaffected** by `agentTool` — they still show
  the full `@UnsafeWeb` set. The two surfaces are intentionally distinct.
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

> **Build & run.** The handler beans carry `@io.quarkus.arc.Unremovable`, so they
> survive Quarkus Arc's default unused-bean removal — the endpoints are reachable in
> a **default consumer**, JVM and native, with no consumer action:
>
> ```bash
> gradle :examples:quickstart:quarkusBuild -x test
> java -jar examples/quickstart/build/quarkus-app/quarkus-run.jar
> ```
>
> Startup logs the surface coming up:
>
> ```text
> HttpHandlerExpose  GET [/agent/discover]
> HttpHandlerExpose  POST [/agent/invoke]
> HttpHandlerExpose  ***** 【 DEV 】 HTTP Server 2 endpoints  on 8080
> RpcServiceExpose   ***** 【 DEV 】 RpcServer expose 1 services on 50051
> ```
>
> (With the MCP bridge enabled — `KRPC_MCP=true` — the POST line also lists `/mcp`
> and the count is 3; see [MCP bridge](#mcp-bridge-post-mcp).)

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

## MCP bridge (`POST /mcp`)

The same server also speaks [MCP](https://modelcontextprotocol.io) (spec
`2025-06-18`, JSON-RPC 2.0 over Streamable HTTP) on the same `8080` host —
**default OFF**, enabled with `rpc.server.mcp.enabled=true` (env `KRPC_MCP=true`).
MCP tools are the **`@UnsafeWeb(agentTool=true)` subset only**; `@UnsafeWeb` alone
does not create a tool. `tools/call` runs the identical credential + filter dispatch
as `/agent/invoke` (credential not bypassed). Full contract: [SPEC §12.2](../SPEC.md#122-mcp-bridge-agent-tools-over-post-mcp).

Run the quickstart with the flag on, then handshake with any MCP client (or curl):

```bash
KRPC_MCP=true java -jar examples/quickstart/build/quarkus-app/quarkus-run.jar
```

```bash
H=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
# 1. initialize
curl "${H[@]}" -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"c","version":"1"}}}'
# -> {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"krpc","version":"1.0.0"}}}

# 2. initialized notification -> HTTP 202, empty body
curl "${H[@]}" -X POST http://127.0.0.1:8080/mcp -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 3. tools/list -> Hello_hello with input/outputSchema (name required, minLength 1 from @NotBlank)
curl "${H[@]}" -X POST http://127.0.0.1:8080/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 4. tools/call -> content + structuredContent (RpcResult data, unwrapped)
curl "${H[@]}" -X POST http://127.0.0.1:8080/mcp \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"Hello_hello","arguments":{"name":"mcp"}}}'
# -> {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"message\":\"Hello, mcp!\",...}"}],"isError":false,"structuredContent":{"message":"Hello, mcp!","timestamp":...}}}
```

With MCP OFF (default), `POST /mcp` is absent (`404`) and the surface is byte-for-byte
the P0 two-endpoint set above.

## Still deferred to the gateway

- **In-core auth / rate limiting.** Both the agent surface and the MCP bridge leave
  authn/throttling to the gateway (ADR-0004); MCP's OAuth 2.1 mapping likewise.

See [ADR-0004](decisions/ADR-0004-agent-friendly-introspection.md) and the
[active roadmap](roadmap/active-roadmap.md) AGENT-001 entry for phasing.
