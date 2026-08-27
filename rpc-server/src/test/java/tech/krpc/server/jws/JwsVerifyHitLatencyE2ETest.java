/**
 * KRPC-JWKS-001 E2E: the HIT-path JWKS refresh must not appear in a real client's call latency.
 *
 * <p>Topology — nothing is faked or short-circuited:
 * <ul>
 *   <li>the production krpc gRPC server ({@code RpcServerBuilder}) on a loopback port with the
 *       production virtual-thread executor, serving a {@code requireCredential} service;</li>
 *   <li>a real krpc client ({@code RpcClientFactory} over a {@code ManagedChannel}) sending a real
 *       ES256 JWT as {@code Authorization: Bearer ...} — the LATENCY IS MEASURED AROUND THAT CALL,
 *       client-side;</li>
 *   <li>the real {@link JwsVerify} inside the server process, fetching from a loopback JWKS
 *       endpoint through its real {@link java.net.http.HttpClient}. The endpoint sleeps
 *       {@link #JWKS_DELAY_MILLIS} before responding: a REAL slow fetch, not a stubbed one.</li>
 * </ul>
 *
 * <p>The only injected value is the refresh window's clock ({@code lastTryFetch}/{@code lastOkFetch}),
 * used exclusively to make the window EXPIRED. It never replaces the JWKS I/O and is not part of
 * any timing measurement.
 *
 * <p>Uses only the pre-existing package-private surface, so the SAME file runs against the pre-fix
 * product — the two latency numbers it prints ({@code KRPC-JWKS-001-E2E ...}) are comparable
 * across the fix.
 */
package tech.krpc.server.jws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;
import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.ServerContext;
import tech.krpc.server.exe.ThreadPool;

class JwsVerifyHitLatencyE2ETest {

    static final String APP = "jwks-hit-latency-e2e";
    static final String KID = "e2e-key";
    static final String SUB = "user-4711";
    static final String JWKS_PATH = "/.well-known/jwks.json";
    /** Real wall-clock cost of one JWKS fetch, served over loopback. */
    static final long JWKS_DELAY_MILLIS = 500L;

    @UnsafeWeb(requireCredential = true)
    @RpcService("AuthEcho")
    public interface AuthEchoService {
        RpcResult<String> whoAmI();
    }

    static class AuthEchoServiceImpl implements AuthEchoService {
        @Override
        public RpcResult<String> whoAmI() {
            return RpcResult.ok(ServerContext.current().uid());
        }
    }

    HttpServer jwksServer;
    ExecutorService jwksPool;
    ExecutorService serverExecutor;
    Server rpcServer;
    ManagedChannel channel;

    final AtomicInteger fetchStarted = new AtomicInteger();
    final AtomicInteger fetchServed = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (rpcServer != null) {
            rpcServer.shutdownNow();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
        if (jwksPool != null) {
            jwksPool.shutdownNow();
        }
        ServerContext.regCredentialVerify(null);
    }

    @Test
    void staleWindowDoesNotShowUpInClientCallLatency() throws Exception {
        var kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        String priB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(kp.getPrivate().getEncoded());
        String jwks = jwksJson(KID, (ECPublicKey) kp.getPublic());
        String token = signJwt(priB64, KID, SUB);

        startJwks(jwks);
        JwsVerify verify = new JwsVerify(jwksUrl());
        verify.loadJwks(); // bootstrap: one real (delayed) fetch, outside every measurement
        ServerContext.regCredentialVerify(verify);

        int port = freePort();
        serverExecutor = ThreadPool.newExecutor(APP);
        rpcServer = new RpcServerBuilder.Builder(APP, port)
                .executor(serverExecutor)
                .addService(new AuthEchoServiceImpl())
                .build()
                .startServer();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
        Channel authed = ClientInterceptors.intercept(channel, bearer(token));
        AuthEchoService client = new RpcClientFactory(APP, asManaged(authed)).get(AuthEchoService.class);

        // Warm the whole chain (netty channel, proxies, JIT) so the numbers below are not startup
        // noise. The window is FRESH here, so no refresh can be triggered.
        verify.lastTryFetch = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            assertEquals(SUB, ok(client.whoAmI()), "warmup call must authenticate");
        }
        int servedAfterWarmup = fetchServed.get();
        int startedAfterWarmup = fetchStarted.get();

        // CONTROL: fresh window, no refresh — this is what an authenticated call costs.
        long freshWindowMillis = timeCall(client);
        assertEquals(startedAfterWarmup, fetchStarted.get(), "the control call must not refresh");

        // MEASURED CASE: window expired (clock injection ONLY). The refresh is due; the fetch it
        // triggers really costs JWKS_DELAY_MILLIS on the wire.
        verify.lastOkFetch = System.currentTimeMillis() - JwsVerify.GAP_MILL - 1_000L;
        verify.lastTryFetch = verify.lastOkFetch;
        long staleWindowMillis = timeCall(client);
        int servedWhenCallReturned = fetchServed.get();

        System.out.println("KRPC-JWKS-001-E2E jwksDelayMillis=" + JWKS_DELAY_MILLIS
                + " freshWindowCallMillis=" + freshWindowMillis
                + " staleWindowCallMillis=" + staleWindowMillis
                + " refreshServedWhenCallReturned=" + (servedWhenCallReturned - servedAfterWarmup));

        // POSITIVE PROOF that a refresh was really triggered by this call...
        awaitAtLeast(fetchStarted, startedAfterWarmup + 1, "the stale window triggered no JWKS fetch");
        // ...that it had NOT completed when the client call returned...
        assertEquals(servedAfterWarmup, servedWhenCallReturned,
                "the JWKS fetch had already completed when the client call returned — the request "
                        + "waited for it");
        // ...and that it completes for real afterwards.
        awaitAtLeast(fetchServed, servedAfterWarmup + 1, "the deferred JWKS fetch never completed");

        // THE PROPERTY: the client-observed latency does not contain the JWKS round-trip.
        assertTrue(staleWindowMillis < JWKS_DELAY_MILLIS / 2,
                () -> "a stale refresh window cost the client " + staleWindowMillis + "ms with a "
                        + JWKS_DELAY_MILLIS + "ms JWKS endpoint (fresh-window control: "
                        + freshWindowMillis + "ms): the refresh is still on the request path");

        // And the refreshed keyset is live afterwards: the call keeps working.
        assertEquals(SUB, ok(client.whoAmI()), "authentication still works after the refresh");
    }

    // --- helpers ---------------------------------------------------------------------------

    private static long timeCall(AuthEchoService client) {
        long t0 = System.nanoTime();
        String uid = ok(client.whoAmI());
        long millis = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(SUB, uid, "the measured call must be a successful authenticated call");
        return millis;
    }

    private static String ok(RpcResult<String> r) {
        assertTrue(r.isOk(), () -> "rpc failed: code=" + r.getCode() + " msg=" + r.getMsg());
        return r.getData();
    }

    private static void awaitAtLeast(AtomicInteger counter, int expected, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (counter.get() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(message + " (counter=" + counter.get() + ", expected >= "
                        + expected + ")");
            }
            Thread.sleep(10);
        }
    }

    private void startJwks(String body) throws Exception {
        jwksPool = Executors.newCachedThreadPool();
        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwksServer.setExecutor(jwksPool);
        jwksServer.createContext(JWKS_PATH, ex -> {
            fetchStarted.incrementAndGet();
            try {
                Thread.sleep(JWKS_DELAY_MILLIS); // a genuinely slow IdP, on the wire
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
            fetchServed.incrementAndGet();
        });
        jwksServer.start();
    }

    private String jwksUrl() {
        return "http://127.0.0.1:" + jwksServer.getAddress().getPort() + JWKS_PATH;
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
                        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                                "Bearer " + token);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }

    /** RpcClientFactory wants a ManagedChannel; route calls through the intercepted Channel. */
    private ManagedChannel asManaged(Channel intercepted) {
        return new ManagedChannel() {
            @Override
            public <Req, Resp> ClientCall<Req, Resp> newCall(MethodDescriptor<Req, Resp> md,
                                                             CallOptions opts) {
                return intercepted.newCall(md, opts);
            }

            @Override
            public String authority() {
                return intercepted.authority();
            }

            @Override
            public ManagedChannel shutdown() {
                return channel.shutdown();
            }

            @Override
            public boolean isShutdown() {
                return channel.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return channel.isTerminated();
            }

            @Override
            public ManagedChannel shutdownNow() {
                return channel.shutdownNow();
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                    throws InterruptedException {
                return channel.awaitTermination(timeout, unit);
            }
        };
    }

    private static String signJwt(String priB64, String kid, String sub) {
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String header64 = Es256Signature.base64(
                ("{\"kid\":\"" + kid + "\",\"alg\":\"ES256\"}").getBytes(StandardCharsets.UTF_8));
        String payload64 = Es256Signature.base64(
                ("{\"sub\":\"" + sub + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return new Es256Signature(priB64).sign(header64, payload64);
    }

    private static String jwksJson(String kid, ECPublicKey pub) {
        var w = pub.getW();
        return "{\"keys\":[{\"kty\":\"EC\",\"use\":\"sig\",\"crv\":\"P-256\",\"kid\":\"" + kid
                + "\",\"x\":\"" + coord(w.getAffineX()) + "\",\"y\":\"" + coord(w.getAffineY())
                + "\"}]}";
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

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
