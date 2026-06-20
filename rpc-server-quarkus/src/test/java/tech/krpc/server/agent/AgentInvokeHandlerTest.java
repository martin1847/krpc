package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import tech.krpc.internal.OutputProto;
import tech.krpc.server.ServerResult;
import tech.krpc.server.WebInvoker;

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

    @Test
    void outputToJson_bytesPayloadBase64() {
        var output = OutputProto.newBuilder().setC(0).setBs(new byte[]{1, 2, 3}).build();
        var json = AgentInvokeHandler.outputToJson(output);
        assertTrue(json.startsWith("{\"code\":0,\"data\":\""), json);
    }
}
