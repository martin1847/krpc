package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.grpc.Status;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import tech.krpc.internal.OutputProto;
import tech.krpc.server.ServerResult;
import tech.krpc.server.WebInvoker;
import tech.krpc.server.invoke.ValidationException;

/**
 * AGENT-001 P0: invoke endpoint rejection + dispatch behavior.
 */
class AgentInvokeHandlerTest {

    private AgentInvokeHandler handler(Map<String, WebInvoker> methods) {
        var registry = new WebMethodRegistry();
        registry.init(methods, null);
        var handler = new AgentInvokeHandler();
        handler.registry = registry;
        return handler;
    }

    private AgentInvokeRequest req(String service, String method) {
        var r = new AgentInvokeRequest();
        r.setService(service);
        r.setMethod(method);
        return r;
    }

    private static String body(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private final HttpHeaders empty = new DefaultHttpHeaders();

    @Test
    void unknownService_rejectedAsNotFound() {
        var h = handler(Map.of());
        var out = body(h.handle(req("Ghost", "boo"), new ArrayList<>(), empty));
        assertTrue(out.contains("\"code\":" + AgentInvokeHandler.CODE_NOT_FOUND), out);
        assertTrue(out.contains("not found"), out);
    }

    @Test
    void hiddenService_notInRegistry_rejected() {
        // Registry only ever holds web methods; a hidden service is simply absent.
        WebInvoker web = (in, headers) -> new ServerResult(OutputProto.newBuilder().setC(0).build());
        var h = handler(Map.of("WebEcho/echo", web));

        var out = body(h.handle(req("HiddenAdmin", "secret"), new ArrayList<>(), empty));
        assertTrue(out.contains("\"code\":" + AgentInvokeHandler.CODE_NOT_FOUND), out);
        // Must not leak anything about the hidden service beyond the opaque not-found.
        assertFalse(out.toLowerCase().contains("secret:"), out);
    }

    @Test
    void missingServiceOrMethod_rejected() {
        var h = handler(Map.of());
        var out = body(h.handle(req(null, "x"), new ArrayList<>(), empty));
        assertTrue(out.contains("\"code\":" + AgentInvokeHandler.CODE_NOT_FOUND), out);
    }

    @Test
    void knownService_dispatchesAndReturnsJson() {
        WebInvoker web = (in, headers) -> {
            var output = OutputProto.newBuilder().setC(0).setUtf8("\"pong\"").build();
            return new ServerResult(output);
        };
        var h = handler(Map.of("WebEcho/echo", web));

        var out = body(h.handle(req("WebEcho", "echo"), new ArrayList<>(), empty));
        assertEquals("{\"code\":0,\"data\":\"pong\"}", out);
    }

    // ----------------------------------------------------------------------------------------
    // AGENT-ERRCODE: the code must come from the Status that UnaryMethod.toClientError attached,
    // not from a blanket INTERNAL. Before this, EVERY throwable here became 13 -- a malformed body,
    // a failed field validation and a real crash were indistinguishable to an agent client.
    //
    // The invoker below stands in for UnaryMethod.invokeWeb, which now applies the shared mapping
    // before the exception reaches this handler; UnaryMethodErrorMappingTest owns the mapping
    // itself, these own the handler's derivation of it.
    // ----------------------------------------------------------------------------------------

    private static AgentInvokeHandler throwingHandler(Throwable toThrow) {
        WebInvoker web = (in, headers) -> {
            throw toThrow;
        };
        var registry = new WebMethodRegistry();
        registry.init(Map.of("WebEcho/echo", web), null);
        var handler = new AgentInvokeHandler();
        handler.registry = registry;
        return handler;
    }

    private String invokeExpectingError(Throwable toThrow) {
        return body(throwingHandler(toThrow).handle(req("WebEcho", "echo"), new ArrayList<>(), empty));
    }

    /** A JSON scalar the strict decoder rejects: client error, INVALID_ARGUMENT(3) — was 13. */
    @Test
    void malformedBody_isInvalidArgument_notInternal() {
        var mapped = Status.INVALID_ARGUMENT
                .withDescription(":trace,malformed JSON request body").asRuntimeException();

        var out = invokeExpectingError(mapped);

        assertTrue(out.contains("\"code\":3"), out);
        assertFalse(out.contains("\"code\":13"), () -> "must no longer report INTERNAL: " + out);
        assertTrue(out.contains("malformed JSON request body"), out);
    }

    /**
     * A missing/blank required field: INVALID_ARGUMENT(3) with the field detail — was 13.
     * This is the exact input a downstream consumer recorded as {@code code=13 ValidationException}.
     */
    @Test
    void validationFailure_isInvalidArgumentWithFieldDetail_notInternal() {
        var validation = new ValidationException("HelloRequest",
                List.of(new ValidationException.Violation("name", "must not be blank")));

        var out = invokeExpectingError(validation);

        assertTrue(out.contains("\"code\":3"), out);
        assertFalse(out.contains("\"code\":13"), () -> "must no longer report INTERNAL: " + out);
        assertTrue(out.contains("name(must not be blank)"), out);
    }

    /** Auth failures keep their own code rather than being flattened. */
    @Test
    void credentialFailure_keepsItsOwnStatusCode() {
        var out = invokeExpectingError(
                Status.UNAUTHENTICATED.withDescription("requireCredential but empty token")
                        .asRuntimeException());
        assertTrue(out.contains("\"code\":16"), out);
    }

    /**
     * A genuinely unexpected server-side failure. The shared mapping classifies it as UNKNOWN(2) —
     * the SAME code the gRPC face has always returned for an unhandled exception. It is not 13:
     * agreeing with gRPC is the entire point of collapsing the three mappings into one.
     */
    @Test
    void unexpectedServerFailure_isUnknown_matchingTheGrpcFace() {
        var mapped = Status.UNKNOWN.withDescription(":trace,IllegalStateException,db pool exhausted")
                .asRuntimeException();

        var out = invokeExpectingError(mapped);

        assertTrue(out.contains("\"code\":2"), out);
    }

    /**
     * The last-resort branch is still wired: a throwable carrying NO Status at all falls back to
     * CODE_INTERNAL. UnaryMethod.invokeWeb's mapping should make this unreachable in production, so
     * this test exists to prove the safety net was not deleted along with the hardcoding.
     */
    @Test
    void throwableWithoutStatus_stillFallsBackToInternal() {
        var out = invokeExpectingError(new IllegalStateException("bypassed the mapping somehow"));

        assertTrue(out.contains("\"code\":" + AgentInvokeHandler.CODE_INTERNAL), out);
        // AGENT-ERRCODE-SEC: the class name used to be the message. It is server-internal, so the
        // client now gets only the generic text; the full throwable goes to the ERROR log instead.
        assertFalse(out.contains("IllegalStateException"),
                () -> "the thrown class must not reach the client: " + out);
        assertTrue(out.contains("internal error"), out);
    }

    @Test
    void outputToJson_bytesPayloadBase64() {
        var output = OutputProto.newBuilder().setC(0).setBs(new byte[]{1, 2, 3}).build();
        var json = AgentInvokeHandler.outputToJson(output);
        assertTrue(json.startsWith("{\"code\":0,\"data\":\""), json);
    }
}
