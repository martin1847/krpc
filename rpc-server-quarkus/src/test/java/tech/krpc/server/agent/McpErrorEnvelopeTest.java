package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.grpc.Status;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import tech.krpc.common.meta.Anno;
import tech.krpc.common.meta.Api;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.common.meta.Dto;
import tech.krpc.common.meta.Method;
import tech.krpc.common.meta.Property;
import tech.krpc.common.meta.PropertyType;
import tech.krpc.internal.OutputProto;
import tech.krpc.server.ServerResult;
import tech.krpc.server.invoke.ValidationException;
import tech.krpc.server.WebInvoker;
import tech.krpc.util.JsonUtils;

/**
 * AGENT-002 (headline): {@code tools/call} error paths must return a structured envelope
 * {@code {code, message, violations?}} as JSON in the tool result content, {@code isError:true}
 * — not a bare exception class name / bare business message.
 *
 * <p>Field-evidence reproductions (verified against code):
 * <ul>
 *   <li><b>Case 1</b> — missing required param: a jakarta validation failure is carried as a
 *       typed {@link ValidationException} ({field, constraint} only, never the rejected value).
 *       The envelope names the violating field with a generic message and NO {@code rejected}
 *       key (AGENT-002 F1: rejected values may be secrets and must never surface).</li>
 *   <li><b>Case 2</b> — wrong entity id: a business {@code NOT_FOUND} must carry code AND
 *       message. On dev a thrown business status collapses to {@code "StatusRuntimeException"}
 *       and a non-zero {@code RpcResult} collapses to a bare message string — the {@code
 *       {code,message}} envelope is absent.</li>
 * </ul>
 */
class McpErrorEnvelopeTest {

    // --- fixture: one agentTool service "Calc" with add(AddReq) --------------------------

    private static ApiMeta calcApiMeta() {
        var nameField = new Property("name", scalar("String"),
                List.of(new Anno("NotBlank", Map.of())));
        var addReq = new Dto("AddReq", 0, true, null);
        addReq.setFields(List.of(nameField));

        var add = new Method();
        add.setName("add");
        add.setArg(new PropertyType(addReq));

        var find = new Method();
        find.setName("find");
        find.setArg(new PropertyType(addReq));

        var api = new Api();
        api.setName("Calc");
        api.setMethods(List.of(add, find));

        return new ApiMeta("test-app", List.of(api), List.of());
    }

    private static PropertyType scalar(String typeName) {
        return new PropertyType(new Dto(typeName, 0, false, null));
    }

    private static McpHandler handler(Map<String, WebInvoker> webKeyed) {
        var registry = new McpToolRegistry();
        registry.init(webKeyed, calcApiMeta());
        var handler = new McpHandler();
        handler.registry = registry;
        return handler;
    }

    // --- helpers ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpHandler h, String body) {
        byte[] out = h.handle(body, new ArrayList<>(), new DefaultHttpHeaders());
        return (Map<String, Object>) JsonUtils.parse(new String(out, StandardCharsets.UTF_8), Object.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> env) {
        return (Map<String, Object>) env.get("result");
    }

    @SuppressWarnings("unchecked")
    private static String firstText(Map<String, Object> env) {
        var content = (List<Map<String, Object>>) result(env).get("content");
        assertFalse(content.isEmpty(), "content block empty: " + env);
        return (String) content.get(0).get("text");
    }

    /** The error content text parsed as the JSON envelope (fails loudly on a bare string). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelope(Map<String, Object> env) {
        String text = firstText(env);
        Object parsed = JsonUtils.parse(text, Object.class);
        assertInstanceOf(Map.class, parsed,
                "error content must be a JSON envelope, not a bare string: " + text);
        return (Map<String, Object>) parsed;
    }

    private static String toolsCall(String toolName, String argumentsJson) {
        var args = null == argumentsJson ? "" : ",\"arguments\":" + argumentsJson;
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\"" + args + "}}";
    }

    // --- Case 0: AGENT-ERRCODE — a malformed body is a client error on this face too ------

    /**
     * A JSON scalar the strict decoder rejects. {@code UnaryMethod.invokeWeb} now applies the
     * shared exception -> Status mapping before the exception reaches this handler, so
     * {@code Status.fromThrowable} finds a real INVALID_ARGUMENT instead of defaulting to
     * UNKNOWN(2). Previously the raw {@code JsonDecodeException} escaped unmapped and every
     * malformed request reported {@code code:2} — "the server broke" — to the agent.
     */
    @Test
    void toolsCall_malformedBody_isInvalidArgument_notUnknown() {
        WebInvoker web = (in, md) -> {
            throw Status.INVALID_ARGUMENT
                    .withDescription(":trace,malformed JSON request body").asRuntimeException();
        };
        var h = handler(Map.of("Calc/add", web));

        var env = call(h, toolsCall("Calc_add", "{\"name\":12345}"));
        assertTrue(Boolean.TRUE.equals(result(env).get("isError")), "decode failure -> isError:true");

        var envelope = envelope(env);
        assertEquals(Status.Code.INVALID_ARGUMENT.value(),
                ((Number) envelope.get("code")).intValue(),
                "a malformed body is INVALID_ARGUMENT(3), no longer UNKNOWN(2)");
        assertNotEquals(Status.Code.UNKNOWN.value(), ((Number) envelope.get("code")).intValue());
    }

    /**
     * The counterpart: a genuinely unexpected server-side failure stays UNKNOWN(2), matching the
     * gRPC face. Proves the fix sharpened client errors without flattening everything to 3.
     */
    @Test
    void toolsCall_unexpectedServerFailure_staysUnknown() {
        WebInvoker web = (in, md) -> {
            throw Status.UNKNOWN.withDescription(":trace,IllegalStateException,db pool exhausted")
                    .asRuntimeException();
        };
        var h = handler(Map.of("Calc/add", web));

        var envelope = envelope(call(h, toolsCall("Calc_add", "{}")));
        assertEquals(Status.Code.UNKNOWN.value(), ((Number) envelope.get("code")).intValue());
    }

    // --- Case 1: validation failure names the field -------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void toolsCall_validationFailure_envelopeNamesFieldGenericMessageNoRejected() {
        // ValidatorInvoke raises a typed ValidationException carrying {field, constraint} only.
        WebInvoker web = (in, md) -> {
            throw new ValidationException("AddReq", List.of(
                    new ValidationException.Violation("name", "must not be blank")));
        };
        var h = handler(Map.of("Calc/add", web));

        var env = call(h, toolsCall("Calc_add", "{}"));
        assertTrue(Boolean.TRUE.equals(result(env).get("isError")), "validation failure -> isError:true");

        var envelope = envelope(env);
        assertEquals(Status.Code.INVALID_ARGUMENT.value(),
                ((Number) envelope.get("code")).intValue(), "code = gRPC INVALID_ARGUMENT (3)");
        assertEquals("Invalid input", envelope.get("message"), "generic message, never the raw description");

        var violations = (List<Map<String, Object>>) envelope.get("violations");
        assertNotNull(violations, "envelope carries violations[]");
        var byField = violations.stream()
                .filter(v -> "name".equals(v.get("field"))).findFirst();
        assertTrue(byField.isPresent(), "the violating field 'name' MUST be named: " + violations);
        assertEquals("must not be blank", byField.get().get("constraint"), "constraint preserved");
        // F1: no rejected value key, anywhere.
        assertFalse(byField.get().containsKey("rejected"), "no rejected value key: " + byField.get());
    }

    @Test
    void toolsCall_validationFailure_rejectedSecretAppearsNowhere() {
        // F1 end-to-end: even though the DTO field held a secret, ValidationException carries
        // only {field, constraint}; the secret must be absent from the entire serialized response.
        var secret = "hunter2-TOP-SECRET";
        WebInvoker web = (in, md) -> {
            throw new ValidationException("AddReq", List.of(
                    new ValidationException.Violation("password", "must not be blank")));
        };
        var h = handler(Map.of("Calc/add", web));

        byte[] out = h.handle(toolsCall("Calc_add", "{\"password\":\"" + secret + "\"}"),
                new ArrayList<>(), new DefaultHttpHeaders());
        String full = new String(out, StandardCharsets.UTF_8);
        assertFalse(full.contains(secret),
                () -> "rejected secret leaked into the MCP response: " + full);
        assertTrue(full.contains("password"), "the field name is still surfaced for self-correction");
    }

    // --- Case 2a: thrown business NOT_FOUND carries code + message ----------------------

    @Test
    void toolsCall_businessNotFoundThrown_envelopeCarriesCodeAndMessage() {
        WebInvoker web = (in, md) -> {
            throw Status.NOT_FOUND.withDescription("city 999 does not exist").asRuntimeException();
        };
        var h = handler(Map.of("Calc/find", web));

        var env = call(h, toolsCall("Calc_find", "{\"name\":\"x\"}"));
        assertTrue(Boolean.TRUE.equals(result(env).get("isError")), "business error -> isError:true");

        var envelope = envelope(env);
        assertEquals(Status.Code.NOT_FOUND.value(),
                ((Number) envelope.get("code")).intValue(), "code = gRPC NOT_FOUND (5)");
        assertEquals("city 999 does not exist", envelope.get("message"),
                "message preserved (dev collapsed this to bare 'StatusRuntimeException')");
    }

    // --- Case 2b: non-zero RpcResult carries code + message -----------------------------

    @Test
    void toolsCall_businessRpcResultCode_envelopeCarriesCodeAndMessage() {
        WebInvoker web = (in, md) ->
                new ServerResult(OutputProto.newBuilder().setC(5).setM("NOT_FOUND").build());
        var h = handler(Map.of("Calc/find", web));

        var env = call(h, toolsCall("Calc_find", "{\"name\":\"x\"}"));
        assertTrue(Boolean.TRUE.equals(result(env).get("isError")), "code!=0 -> isError:true");

        var envelope = envelope(env);
        assertEquals(5, ((Number) envelope.get("code")).intValue(), "RpcResult code surfaced");
        assertEquals("NOT_FOUND", envelope.get("message"),
                "RpcResult message surfaced (dev emitted a bare string, no envelope)");
    }

    // --- F3: violations come ONLY from the typed channel, never a prose parse ------------

    @Test
    @SuppressWarnings("unchecked")
    void toolsCall_businessInvalidArgumentProse_noPhantomViolations() {
        // A business INVALID_ARGUMENT whose prose merely LOOKS like the old validator format
        // ("Dto : field=value(msg); …", plus '=', '(', ')', ';') must NOT be parsed into
        // violations — that channel is typed-only now.
        WebInvoker web = (in, md) -> {
            throw Status.INVALID_ARGUMENT
                    .withDescription("Order : state=locked(retry later); note=a;b(x)")
                    .asRuntimeException();
        };
        var h = handler(Map.of("Calc/find", web));

        var envelope = envelope(call(h, toolsCall("Calc_find", "{\"name\":\"x\"}")));
        assertEquals(Status.Code.INVALID_ARGUMENT.value(), ((Number) envelope.get("code")).intValue());
        assertEquals("Order : state=locked(retry later); note=a;b(x)", envelope.get("message"),
                "business description surfaced verbatim as message");
        assertFalse(envelope.containsKey("violations"),
                "business prose must NOT be parsed into phantom violations: " + envelope);
    }

    @Test
    void toolsCall_malformedJsonStyleStatus_noPhantomViolations() {
        WebInvoker web = (in, md) -> {
            throw Status.INVALID_ARGUMENT
                    .withDescription("abc123,malformed JSON request body").asRuntimeException();
        };
        var h = handler(Map.of("Calc/find", web));
        var envelope = envelope(call(h, toolsCall("Calc_find", "{\"name\":\"x\"}")));
        assertFalse(envelope.containsKey("violations"), "no phantom violations: " + envelope);
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsCall_nestedValidationCause_violationsStillRecovered() {
        // Status.fromThrowable walks the causal chain; findValidation must too.
        WebInvoker web = (in, md) -> {
            throw new RuntimeException("wrapper", new ValidationException("AddReq", List.of(
                    new ValidationException.Violation("email", "must be a valid email"))));
        };
        var h = handler(Map.of("Calc/add", web));
        var envelope = envelope(call(h, toolsCall("Calc_add", "{}")));
        assertEquals("Invalid input", envelope.get("message"));
        var violations = (List<Map<String, Object>>) envelope.get("violations");
        assertNotNull(violations, "typed violations recovered from a nested cause");
        assertEquals("email", violations.get(0).get("field"));
    }

    // --- unknown tool: did-you-mean -----------------------------------------------------

    @Test
    void toolsCall_unknownTool_suggestsNearestName() {
        // "Calc_ad" is edit-distance 1 from the real "Calc_add"; the -32602 message must
        // append a did-you-mean suggestion.
        var h = handler(Map.of("Calc/add", (in, md) ->
                new ServerResult(OutputProto.newBuilder().setC(0).setUtf8("{}").build())));

        var env = call(h, toolsCall("Calc_ad", "{}"));
        @SuppressWarnings("unchecked")
        var error = (Map<String, Object>) env.get("error");
        assertEquals(-32602, ((Number) error.get("code")).intValue(), "unknown tool -> INVALID_PARAMS");
        var message = (String) error.get("message");
        assertTrue(message.contains("Calc_add"),
                "unknown-tool error must suggest the nearest tool name: " + message);
    }

    // --- empty face: tools/list hint ----------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void toolsList_emptyFace_carriesHintInMeta() {
        var registry = new McpToolRegistry();
        registry.init(Map.of(), new ApiMeta("test-app", List.of(), List.of()));
        var h = new McpHandler();
        h.registry = registry;

        var env = call(h, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        var res = result(env);
        var tools = (List<Object>) res.get("tools");
        assertTrue(tools.isEmpty(), "empty face -> empty tools list (still valid)");

        var meta = (Map<String, Object>) res.get("_meta");
        assertNotNull(meta, "empty face carries a _meta hint");
        var hint = meta.values().stream().map(String::valueOf).findFirst().orElse("");
        assertTrue(hint.contains("agentTool"),
                "hint explains the empty face (KRPC_MCP enabled but no agentTool interfaces): " + meta);
    }
}
