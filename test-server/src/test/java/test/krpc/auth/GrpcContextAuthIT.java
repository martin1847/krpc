package test.krpc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.client.RpcClientFactory;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.ServerContext;
import tech.krpc.server.exe.ThreadPool;
import tech.krpc.server.jws.Es256Signature;
import tech.krpc.server.jws.JwsVerify;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.SerialEnum;
import tech.krpc.server.ServerResult;
import tech.krpc.server.WebInvoker;

/**
 * Real-auth integration test for the ServerContext -> io.grpc.Context migration.
 *
 * <p>Stands up the production krpc gRPC server (RpcServerBuilder) on a loopback port with the
 * production virtual-thread executor (ThreadPool.newExecutor), behind a real JwsVerify that
 * fetches a JWKS over a localhost-only HTTP server. A real ES256 (EC P-256) keypair signs a
 * JWT whose {@code sub} is known; the client sends it as {@code Authorization: Bearer ...}.
 *
 * <p>Asserts: (1) with a valid token, the credential-required service returns
 * {@code ServerContext.current().uid()} == the JWT sub — proving uid() resolves through
 * io.grpc.Context on the VT executor; (2) without a token the call is rejected by
 * checkCredential. No mysql, no external network: the only socket traffic is loopback.
 */
class GrpcContextAuthIT {

    static final String APP = "ctx-auth-it";
    static final String KID = "ctx-it-key";
    static final String EXPECTED_SUB = "user-4711";

    HttpServer jwksServer;
    Server rpcServer;
    int rpcPort;
    ExecutorService executor;
    ManagedChannel clientChannel;
    RpcClientFactory clientFactory;
    RpcServerBuilder serverBuilder; // kept to reach webMethods() for the invokeWeb (Netty-IO) path
    UidEchoServiceImpl serviceImpl; // kept to assert the service-body call counter

    String signedJwt; // valid ES256 JWT with sub == EXPECTED_SUB

    @BeforeEach
    void setUp() throws Exception {
        // 1. Real EC P-256 keypair.
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        var pub = (ECPublicKey) kp.getPublic();
        var priv = (ECPrivateKey) kp.getPrivate();

        // 2. Sign a real ES256 JWT (sub=EXPECTED_SUB) with the private key. Es256Signature
        //    expects a PKCS8 url-base64 private key; getEncoded() is PKCS8 DER.
        String priB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(priv.getEncoded());
        signedJwt = signJwt(priB64, KID, EXPECTED_SUB);

        // 3. Serve a JWKS built from the public key over a loopback-only HTTP server.
        String jwksJson = jwksJson(KID, pub);
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String path = "/.well-known/jwks.json";
        jwksServer.createContext(path, ex -> {
            byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        jwksServer.start();
        String jwksUrl = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + path;

        // 4. Register the real verifier (no client binding so cid is irrelevant) and stand up
        //    the production server on a free loopback port with the production VT executor.
        ServerContext.regCredentialVerify(new JwsVerify(jwksUrl, JwsVerify.DEFAULT_COOKIE_NAME));

        rpcPort = freePort();
        executor = ThreadPool.newExecutor(APP, 6);
        serviceImpl = new UidEchoServiceImpl();
        serverBuilder = new RpcServerBuilder.Builder(APP, rpcPort)
                .executor(executor)
                .addService(serviceImpl)
                .build();
        rpcServer = serverBuilder.startServer();

        clientChannel = ManagedChannelBuilder.forAddress("127.0.0.1", rpcPort)
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() {
        if (clientChannel != null) clientChannel.shutdownNow();
        if (rpcServer != null) rpcServer.shutdownNow();
        if (executor != null) executor.shutdownNow();
        if (jwksServer != null) jwksServer.stop(0);
        ServerContext.regCredentialVerify(null);
    }

    @Test
    void validToken_uidResolvesToJwtSubject() {
        // Client channel that injects Authorization: Bearer <token> on every call.
        Channel authed = ClientInterceptors.intercept(clientChannel, bearer(signedJwt));
        var svc = new RpcClientFactory(APP, asManaged(authed)).get(UidEchoService.class);

        var res = svc.whoAmI();

        assertNotNull(res, "result must not be null");
        assertTrue(res.isOk(), () -> "expected ok result, got code=" + res.getCode() + " msg=" + res.getMsg());
        assertEquals(EXPECTED_SUB, res.getData(),
                "uid() must equal the JWT subject resolved via io.grpc.Context");
    }

    @Test
    void missingToken_callRejected() {
        // No interceptor -> no Authorization header -> checkCredential must reject.
        var svc = new RpcClientFactory(APP, clientChannel).get(UidEchoService.class);

        var ex = assertThrows(StatusRuntimeException.class, svc::whoAmI);
        // Codex Fix 1: a bare StatusRuntimeException is false-assurance — deleting the gate would
        // still throw (uid() NPEs on the null credential, wrapped as UNKNOWN). Prove the rejection
        // came FROM checkCredential: the status must be UNAUTHENTICATED (JwsVerify: empty token)...
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, ex.getStatus().getCode(),
                "no-token must be rejected by checkCredential (UNAUTHENTICATED), not a wrapped NPE");
        // ...and the service body must NOT have executed.
        assertEquals(0, serviceImpl.calls(), "service body ran despite the missing credential");

        // The counter is real and the gate blocked exactly the no-token call: a valid token now
        // drives the same service and the body runs exactly once.
        Channel authed = ClientInterceptors.intercept(clientChannel, bearer(signedJwt));
        var okSvc = new RpcClientFactory(APP, asManaged(authed)).get(UidEchoService.class);
        var ok = okSvc.whoAmI();
        assertTrue(ok.isOk(), () -> "expected ok, got code=" + ok.getCode() + " msg=" + ok.getMsg());
        assertEquals(1, serviceImpl.calls(), "valid token must execute the service body exactly once");
    }

    @Test
    void invokeWeb_isolatesContextAcrossConsecutiveRequestsOnSameThread() throws Exception {
        WebInvoker web = webInvokerFor("whoAmI");
        // A single reused platform thread stands in for the pooled Netty I/O thread that the
        // HTTP/agent invokeWeb path runs on. Reusing the thread is what makes isolation testable.
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            // 1) valid token -> uid resolves via io.grpc.Context; service body ran once.
            ServerResult r1 = io.submit(() -> callWeb(web, bearerMetadata(signedJwt)))
                    .get(10, TimeUnit.SECONDS);
            assertUid(r1);
            assertEquals(1, serviceImpl.calls());
            assertNull(currentContextOn(io),
                    "ServerContext leaked on the I/O thread after a valid-token web request");

            // 2) no token on the SAME thread -> rejected by checkCredential; body NOT run; #1's
            //    context did NOT bleed in (the request is freshly absent, then rejected).
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> io.submit(() -> callWeb(web, new Metadata())).get(10, TimeUnit.SECONDS));
            assertUnauthenticated(ee.getCause());
            assertEquals(1, serviceImpl.calls(),
                    "no-token web request must not execute the service body");
            assertNull(currentContextOn(io),
                    "ServerContext leaked on the I/O thread after a no-token web request");

            // 3) valid token again on the SAME thread -> resolves freshly (not bled from #2).
            ServerResult r3 = io.submit(() -> callWeb(web, bearerMetadata(signedJwt)))
                    .get(10, TimeUnit.SECONDS);
            assertUid(r3);
            assertEquals(2, serviceImpl.calls());
            assertNull(currentContextOn(io));
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void invokeWeb_restoresCleanContextAfterServiceException() throws Exception {
        WebInvoker whoAmI = webInvokerFor("whoAmI");
        WebInvoker boom = webInvokerFor("boom");
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            // 1) valid token, but the service body throws. invokeWeb must still run its finally
            //    (detach) before the exception propagates out — proving no leftover context.
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> io.submit(() -> callWeb(boom, bearerMetadata(signedJwt)))
                            .get(10, TimeUnit.SECONDS));
            assertNotNull(ee.getCause(), "service exception must propagate");
            assertNull(currentContextOn(io),
                    "ServerContext leaked on the I/O thread after a service exception");

            // 2) the next request on the SAME thread sees a clean context and resolves correctly.
            ServerResult r = io.submit(() -> callWeb(whoAmI, bearerMetadata(signedJwt)))
                    .get(10, TimeUnit.SECONDS);
            assertUid(r);
            assertNull(currentContextOn(io));
        } finally {
            io.shutdownNow();
        }
    }

    // --- AGENT-001 Fix 2 helpers (invokeWeb / Netty-IO path) ----------------------------

    /** The web dispatcher (UnaryMethod) the HTTP/agent path resolves for {@code method}. */
    private WebInvoker webInvokerFor(String method) {
        return serverBuilder.webMethods().entrySet().stream()
                .filter(e -> e.getKey().endsWith("/" + method))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no web method '" + method + "' in " + serverBuilder.webMethods().keySet()));
    }

    // invokeWeb declares `throws Throwable`; Callable only permits Exception, so adapt here.
    private static ServerResult callWeb(WebInvoker web, Metadata headers) throws Exception {
        try {
            return web.invokeWeb(jsonInput(), headers);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Minimal JSON-serial InputProto; whoAmI/boom take no args so no payload is needed. */
    private static InputProto jsonInput() {
        return InputProto.newBuilder().setE(SerialEnum.JSON).build();
    }

    private static Metadata bearerMetadata(String token) {
        var md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return md;
    }

    /** Reads ServerContext.current() ON the executor's thread (where invokeWeb just ran). */
    private static ServerContext currentContextOn(ExecutorService io) throws Exception {
        return io.submit(ServerContext::current).get(10, TimeUnit.SECONDS);
    }

    private static void assertUid(ServerResult r) {
        assertNotNull(r, "web result must not be null");
        assertEquals(0, r.output.getC(),
                () -> "expected ok code, got " + r.output.getC() + "/" + r.output.getM());
        assertEquals("\"" + EXPECTED_SUB + "\"", r.output.getUtf8(),
                "uid must resolve to the JWT subject on the web/IO path");
    }

    private static void assertUnauthenticated(Throwable t) {
        assertNotNull(t, "expected a cause");
        io.grpc.Status status;
        if (t instanceof io.grpc.StatusException se) {
            status = se.getStatus();
        } else if (t instanceof StatusRuntimeException sre) {
            status = sre.getStatus();
        } else {
            throw new AssertionError("expected a gRPC Status exception, got " + t, t);
        }
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, status.getCode(),
                "web no-token must be rejected by checkCredential (UNAUTHENTICATED)");
    }

    // --- helpers -----------------------------------------------------------------------

    // RpcClientFactory needs a ManagedChannel; the intercepted Channel is a plain Channel.
    // We keep the underlying ManagedChannel for lifecycle and route calls through the
    // intercepted Channel via a thin ManagedChannel wrapper.
    private ManagedChannel asManaged(Channel intercepted) {
        return new ManagedChannel() {
            @Override
            public <Req, Resp> ClientCall<Req, Resp> newCall(
                    MethodDescriptor<Req, Resp> md, CallOptions opts) {
                return intercepted.newCall(md, opts);
            }

            @Override
            public String authority() {
                return intercepted.authority();
            }

            @Override
            public ManagedChannel shutdown() {
                return clientChannel.shutdown();
            }

            @Override
            public boolean isShutdown() {
                return clientChannel.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return clientChannel.isTerminated();
            }

            @Override
            public ManagedChannel shutdownNow() {
                return clientChannel.shutdownNow();
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                    throws InterruptedException {
                return clientChannel.awaitTermination(timeout, unit);
            }
        };
    }

    private static ClientInterceptor bearer(String token) {
        return new ClientInterceptor() {
            @Override
            public <Req, Resp> ClientCall<Req, Resp> interceptCall(
                    MethodDescriptor<Req, Resp> method, CallOptions callOptions, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(
                        next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<Resp> responseListener, Metadata headers) {
                        headers.put(
                                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                                "Bearer " + token);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }

    /** Sign a minimal ES256 JWT {kid,alg=ES256}.{sub,exp} using the production signer. */
    private static String signJwt(String priB64, String kid, String sub) {
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String headerJson = "{\"kid\":\"" + kid + "\",\"alg\":\"ES256\"}";
        String payloadJson = "{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}";
        String header64 = Es256Signature.base64(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload64 = Es256Signature.base64(payloadJson.getBytes(StandardCharsets.UTF_8));
        return new Es256Signature(priB64).sign(header64, payload64);
    }

    /** Build a JWKS document containing the EC public key (kty=EC, crv=P-256, x, y). */
    private static String jwksJson(String kid, ECPublicKey pub) {
        var w = pub.getW();
        String x = coord(w.getAffineX());
        String y = coord(w.getAffineY());
        return "{\"keys\":[{"
                + "\"kty\":\"EC\","
                + "\"use\":\"sig\","
                + "\"crv\":\"P-256\","
                + "\"kid\":\"" + kid + "\","
                + "\"x\":\"" + x + "\","
                + "\"y\":\"" + y + "\""
                + "}]}";
    }

    /** Fixed-width (32-byte for P-256) url-base64 of an affine coordinate, per JWK spec. */
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

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
