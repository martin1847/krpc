package test.krpc.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        rpcServer = new RpcServerBuilder.Builder(APP, rpcPort)
                .executor(executor)
                .addService(new UidEchoServiceImpl())
                .build()
                .startServer();

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
        // JwsVerify throws UNAUTHENTICATED("requireCredential but empty token"); blocking stub
        // surfaces it as StatusRuntimeException. Any rejection (non-OK status) is sufficient.
        assertNotNull(ex.getStatus());
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
