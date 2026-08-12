package tech.krpc.server.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import io.grpc.Status;
import io.grpc.StatusException;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.common.meta.Api;
import tech.krpc.common.meta.ApiMeta;
import tech.krpc.common.meta.Dto;
import tech.krpc.common.meta.Method;
import tech.krpc.common.meta.Property;
import tech.krpc.common.meta.PropertyType;
import tech.krpc.internal.OutputProto;
import tech.krpc.server.ServerResult;
import tech.krpc.server.WebInvoker;
import tech.krpc.server.jws.Es256Jws;
import tech.krpc.server.jws.JwsVerify;
import tech.krpc.util.JsonUtils;

/**
 * AGENT-ERRCODE-SEC, integration level: proves the agent face actually CALLS the sanitizer on a
 * real authentication failure.
 *
 * <p>{@code AgentErrorMessageTest} proves the sanitizer collapses auth reasons. That is only half
 * the claim — the other half is that the handler routes a real auth failure through it, and the
 * seam between "the function is correct" and "the function is called" is exactly where a
 * regression hides. So nothing here is a hand-written {@code Status}: a REAL {@link JwsVerify},
 * loaded from a REAL loopback JWKS endpoint, rejects REAL tokens, and the resulting exception is
 * handed to the REAL {@link AgentInvokeHandler} and the REAL {@link McpHandler}.
 *
 * <p>MCP is covered as well as {@code /agent/invoke} because it is the MORE exposed of the two:
 * an agent connects to it directly. The two faces share {@link AgentErrorMessage}, but sharing a
 * helper is not evidence that both call it.
 *
 * <p><b>What is and is not exercised.</b> The handler is invoked at {@code handle(...)} — the same
 * entry point the netty pipeline calls — rather than over a socket. The transport cannot change
 * the response body, which is what these assertions are about, so the omission is deliberate and
 * not a coverage gap for this claim. The one link taken on trust is that
 * {@code UnaryMethod.invokeWeb} rethrows a {@code Status} carrier unchanged, which
 * {@code UnaryMethodErrorMappingTest#existingStatusCarriers_passThroughUnchanged} pins separately;
 * {@code ServerContext.checkCredential} throws exactly the {@code StatusException} that
 * {@code JwsVerify.verify} produces here.
 *
 * <p>Assertions are EXACT equality on the message, not {@code contains} — a tail of leaked reason
 * would still satisfy {@code contains}.
 */
class AgentAuthDisclosureTest {

    private static final String JWKS_PATH = "/.well-known/jwks.json";
    private static final String CID = "cid-42";

    /** Identifiers a rejected caller must never be handed back. */
    private static final String[] FORBIDDEN = {
            "kid", "exp", "nbf", "aud", "client-id", CID,
            "jwks", "signature", "token", "Exception", "expired", "not found",
    };

    private HttpServer jwksServer;
    private JwsVerify verify;
    private Kp trusted;
    private Kp untrusted;

    record Kp(String kid, String priB64, ECPublicKey pub) {}

    @BeforeEach
    void startJwks() throws Exception {
        trusted = genKey("TRUSTED-KID");
        untrusted = genKey("ROGUE-KID");

        var body = "{\"keys\":[" + jwkEntry(trusted) + "]}";
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.createContext(JWKS_PATH, ex -> {
            byte[] out = body.getBytes(UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        jwksServer.start();

        verify = new JwsVerify("http://127.0.0.1:" + jwksServer.getAddress().getPort() + JWKS_PATH);
        verify.loadJwks();
    }

    @AfterEach
    void stopJwks() {
        if (null != jwksServer) {
            jwksServer.stop(0);
            jwksServer = null;
        }
    }

    // ---------------------------------------------------------------- the real rejections

    /** Precondition: these tokens really are rejected, and really do carry a talkative reason. */
    @Test
    void precondition_realVerifierRejectsWithADetailedReason() {
        var rejected = assertThrows(StatusException.class,
                () -> verify.verify(jwt(untrusted, +3600), CID, false));

        var reason = rejected.getStatus().getDescription();
        assertNotNull(reason, "the verifier's own reason must be non-empty for this test to mean anything");
        assertFalse(reason.isBlank(), reason);
    }

    /** An unknown kid: signed by a key the JWKS never published. */
    @Test
    void unknownKid_clientSeesOnlyTheGenericString() {
        assertSanitized(jwt(untrusted, +3600), "unknown kid");
    }

    /** A forged signature: the trusted kid, signed with the rogue key. */
    @Test
    void invalidSignature_clientSeesOnlyTheGenericString() {
        var forged = new Es256Jws(trusted.kid(), untrusted.priB64())
                .jws("user-1", epoch(+3600), m -> {});
        assertSanitized(forged, "forged signature");
    }

    /** An expired token signed by the trusted key. */
    @Test
    void expiredToken_clientSeesOnlyTheGenericString() {
        assertSanitized(jwt(trusted, -3600), "expired token");
    }

    /** A structurally broken token. */
    @Test
    void malformedToken_clientSeesOnlyTheGenericString() {
        assertSanitized("not.a.jwt", "malformed token");
    }

    /** No token at all. */
    @Test
    void emptyToken_clientSeesOnlyTheGenericString() {
        assertSanitized("", "empty token");
    }

    /**
     * The oracle test. Rejections that share a status code must be BYTE-IDENTICAL, so a caller
     * cannot binary-search which part of a forgery failed.
     *
     * <p>Note what this deliberately does NOT claim: {@code UNAUTHENTICATED}(16) and
     * {@code PERMISSION_DENIED}(7) stay distinguishable from each other. That distinction is the
     * classification, it is the same 401-vs-403 split every HTTP API exposes, and a caller needs
     * it to decide "re-authenticate" versus "stop". Sanitizing the reason must not flatten the
     * code — collapsing them would break the error model to buy nothing, since the codes carry no
     * per-token information. The oracle lived in the free-text reason, and that is what is gone.
     */
    @Test
    void rejectionsSharingACode_areByteIdentical() {
        var byCode = new java.util.LinkedHashMap<Integer, java.util.Set<String>>();
        for (var token : new String[]{jwt(untrusted, +3600), jwt(trusted, -3600), "not.a.jwt", "",
                new Es256Jws(trusted.kid(), untrusted.priB64()).jws("u", epoch(+3600), m -> {})}) {
            var body = invoke(token);
            var code = body.contains("\"code\":16") ? 16 : 7;
            byCode.computeIfAbsent(code, k -> new java.util.LinkedHashSet<>()).add(body);
        }

        assertFalse(byCode.isEmpty(), "the fixture must actually produce rejections");
        byCode.forEach((code, bodies) -> assertEquals(1, bodies.size(),
                () -> "code " + code + " has distinguishable bodies — that is the oracle: " + bodies));
    }

    // ---------------------------------------------------------------- the same, on the MCP face

    /**
     * MCP, unknown kid. Same real rejection, different handler: the envelope's {@code message} must
     * be exactly the generic string, the code must survive, and {@code isError} must be true.
     */
    @Test
    void mcp_unknownKid_envelopeCarriesOnlyTheGenericString() {
        assertMcpSanitized(jwt(untrusted, +3600), "unknown kid");
    }

    /** MCP, expired token — a different verifier branch, the same disclosure. */
    @Test
    void mcp_expiredToken_envelopeCarriesOnlyTheGenericString() {
        assertMcpSanitized(jwt(trusted, -3600), "expired token");
    }

    /** Both MCP rejections must be byte-identical when they share a code, for the same reason. */
    @Test
    void mcp_rejectionsSharingACode_areByteIdentical() {
        var byCode = new java.util.LinkedHashMap<Integer, java.util.Set<String>>();
        for (var token : new String[]{jwt(untrusted, +3600), jwt(trusted, -3600), "not.a.jwt", ""}) {
            var envelope = mcpEnvelope(token);
            byCode.computeIfAbsent(((Number) envelope.get("code")).intValue(),
                    k -> new java.util.LinkedHashSet<>()).add(String.valueOf(envelope));
        }
        byCode.forEach((code, envelopes) -> assertEquals(1, envelopes.size(),
                () -> "MCP code " + code + " has distinguishable envelopes: " + envelopes));
    }

    private void assertMcpSanitized(String token, String label) {
        var raw = mcpRaw(token);
        var envelope = parseEnvelope(raw);

        var code = ((Number) envelope.get("code")).intValue();
        assertTrue(16 == code || 7 == code,
                () -> label + ": must stay UNAUTHENTICATED(16) or PERMISSION_DENIED(7): " + raw);
        assertEquals(16 == code ? "unauthenticated" : "permission denied", envelope.get("message"),
                () -> label + ": envelope message must be EXACTLY the generic string: " + raw);
        assertFalse(envelope.containsKey("violations"),
                () -> label + ": an auth failure is not a validation failure: " + raw);

        for (var forbidden : FORBIDDEN) {
            assertFalse(raw.toLowerCase().contains(forbidden.toLowerCase()),
                    () -> label + ": '" + forbidden + "' must not reach a rejected agent: " + raw);
        }
    }

    private Map<String, Object> mcpEnvelope(String token) {
        return parseEnvelope(mcpRaw(token));
    }

    /** The whole JSON-RPC response, so the forbidden-string sweep covers the envelope AND its wrapper. */
    private String mcpRaw(String token) {
        WebInvoker web = (in, md) -> {
            verify.verify(token, CID, false);
            return new ServerResult(OutputProto.newBuilder().setC(0).build());
        };
        var registry = new McpToolRegistry();
        registry.init(Map.of("Secure/op", web), mcpApiMeta());
        var handler = new McpHandler();
        handler.registry = registry;

        var request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"Secure_op\",\"arguments\":{\"name\":\"x\"}}}";
        return new String(handler.handle(request, new ArrayList<>(), new DefaultHttpHeaders()), UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseEnvelope(String raw) {
        var response = (Map<String, Object>) JsonUtils.parse(raw, Object.class);
        var result = (Map<String, Object>) response.get("result");
        assertEquals(Boolean.TRUE, result.get("isError"),
                () -> "an auth rejection must be a tool error: " + raw);
        var content = (List<Map<String, Object>>) result.get("content");
        var parsed = JsonUtils.parse((String) content.get(0).get("text"), Object.class);
        assertTrue(parsed instanceof Map, () -> "error content must be a JSON envelope: " + raw);
        return (Map<String, Object>) parsed;
    }

    /** One agentTool method "Secure/op" taking a DTO with a single String field. */
    private static ApiMeta mcpApiMeta() {
        var req = new Dto("OpReq", 0, true, null);
        req.setFields(List.of(new Property("name",
                new PropertyType(new Dto("String", 0, false, null)), List.of())));
        var op = new Method();
        op.setName("op");
        op.setArg(new PropertyType(req));
        var api = new Api();
        api.setName("Secure");
        api.setMethods(List.of(op));
        return new ApiMeta("test-app", List.of(api), List.of());
    }

    // ---------------------------------------------------------------- unexpected failures

    /** A mapped UNKNOWN (what invokeWeb produces for an unhandled exception) surrenders nothing. */
    @Test
    void unexpectedFailure_mapped_clientSeesOnlyInternalError() {
        var leaky = Status.UNKNOWN.withDescription(
                        ":trace,SQLTransientConnectionException,HikariPool-1 jdbc:mysql://db-prod-3.internal:3306/orders")
                .asRuntimeException();

        var body = invokeThrowing(leaky);

        assertEquals("{\"code\":2,\"message\":\"internal error\"}", body);
        for (var secret : new String[]{"Hikari", "jdbc", "db-prod-3", "SQLTransient", "orders"}) {
            assertFalse(body.contains(secret), () -> secret + " leaked: " + body);
        }
    }

    /** The outside-dispatch fallback (no Status at all) is equally silent to the client. */
    @Test
    void unexpectedFailure_unmapped_clientSeesOnlyInternalError() {
        var body = invokeThrowing(new IllegalStateException("/etc/krpc/secrets.yml unreadable"));

        assertEquals("{\"code\":13,\"message\":\"internal error\"}", body);
        assertFalse(body.contains("IllegalStateException"), body);
        assertFalse(body.contains("secrets.yml"), body);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Drive the real handler with the real verifier's rejection and assert total disclosure
     * control: exact generic message, the right code, and not one forbidden identifier.
     */
    private void assertSanitized(String token, String label) {
        var body = invoke(token);

        var code = body.contains("\"code\":16") ? 16 : body.contains("\"code\":7") ? 7 : -1;
        assertTrue(16 == code || 7 == code,
                () -> label + ": must stay UNAUTHENTICATED(16) or PERMISSION_DENIED(7): " + body);

        var expected = 16 == code
                ? "{\"code\":16,\"message\":\"unauthenticated\"}"
                : "{\"code\":7,\"message\":\"permission denied\"}";
        assertEquals(expected, body, () -> label + ": message must be EXACTLY the generic string");

        for (var forbidden : FORBIDDEN) {
            assertFalse(body.toLowerCase().contains(forbidden.toLowerCase()),
                    () -> label + ": '" + forbidden + "' must not reach a rejected caller: " + body);
        }
    }

    /** Real JwsVerify rejection -> the exception invokeWeb would rethrow -> real handler. */
    private String invoke(String token) {
        return invokeWith((in, md) -> {
            // Mirrors ServerContext.checkCredential: verify() throws a StatusException, which
            // UnaryMethod.toClientError passes through untouched (pinned separately).
            verify.verify(token, CID, false);
            return new ServerResult(OutputProto.newBuilder().setC(0).build());
        });
    }

    private String invokeThrowing(Throwable toThrow) {
        return invokeWith((in, md) -> {
            throw toThrow;
        });
    }

    private String invokeWith(WebInvoker web) {
        var registry = new WebMethodRegistry();
        registry.init(Map.of("Secure/op", web), null);
        var handler = new AgentInvokeHandler();
        handler.registry = registry;

        var req = new AgentInvokeRequest();
        req.setService("Secure");
        req.setMethod("op");
        return new String(handler.handle(req, new ArrayList<>(), new DefaultHttpHeaders()), UTF_8);
    }

    private static String jwt(Kp kp, long offsetSeconds) {
        return new Es256Jws(kp.kid(), kp.priB64()).jws("user-1", epoch(offsetSeconds), m -> {});
    }

    private static long epoch(long offsetSeconds) {
        return System.currentTimeMillis() / 1000L + offsetSeconds;
    }

    private static Kp genKey(String kid) throws Exception {
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        var kp = kpg.generateKeyPair();
        var pri = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(((ECPrivateKey) kp.getPrivate()).getEncoded());
        return new Kp(kid, pri, (ECPublicKey) kp.getPublic());
    }

    private static String jwkEntry(Kp kp) {
        var w = kp.pub().getW();
        return "{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"" + kp.kid()
                + "\",\"x\":\"" + coord(w.getAffineX()) + "\",\"y\":\"" + coord(w.getAffineY()) + "\"}";
    }

    private static String coord(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] fixed = new byte[32];
        if (raw.length == 33 && raw[0] == 0) {
            System.arraycopy(raw, 1, fixed, 0, 32);
        } else if (raw.length <= 32) {
            System.arraycopy(raw, 0, fixed, 32 - raw.length, raw.length);
        } else {
            throw new IllegalStateException("unexpected coord length " + raw.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed);
    }
}
