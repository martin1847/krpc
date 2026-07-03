package tech.krpc.server.quarkus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;

import io.grpc.Status;
import io.grpc.StatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tech.krpc.server.ServerContext;
import tech.krpc.server.jws.JwsVerify;

/**
 * HARDEN-B1 regression guard (quarkus wiring). Mirrors the spring guard: proves that a JWKS load
 * failure at {@code InitJwsVerify.init()} leaves auth FAIL-CLOSED — a verifier IS registered and it
 * REJECTS every credentialed request with {@code UNAVAILABLE} — never fail-open.
 *
 * <p>Pre-fix bug: on JWKS load failure, {@code init()} left {@code ServerContext.credentialVerify}
 * null, so {@code ServerContext.verify()} skipped the credential check entirely — auth silently
 * disabled (fail-open). The fix delegates to {@link JwsVerify#bootstrapAndRegister} which ALWAYS
 * registers the verifier (unless scheme (b) aborts startup); a not-ready verifier rejects rather
 * than being absent.
 *
 * <p>Plain JUnit5: {@code InitJwsVerify} is constructed by hand (no Quarkus container, so
 * {@code @ConfigProperty}/{@code @Inject} are irrelevant) and its package-private fields are set
 * directly from this same-package test.
 */
class InitJwsVerifyFailClosedTest {

    /**
     * A URL pointing at a port that was opened then immediately closed — nothing listens, so the
     * synchronous JWKS fetch fails deterministically (connection refused) without a wall-clock wait.
     */
    private static String refusedUrl() throws IOException {
        int port;
        try (ServerSocket sock = new ServerSocket(0)) {
            port = sock.getLocalPort();
        }
        return "http://127.0.0.1:" + port + "/";
    }

    /** Build a hand-wired InitJwsVerify whose JWKS URL is a refused (dead) port. */
    private static InitJwsVerify failingInit(String url) {
        var init = new InitJwsVerify();
        init.jwks = Optional.of(url);
        init.jwsCookie = JwsVerify.DEFAULT_COOKIE_NAME;
        init.exitOnJwksError = false;
        init.bindClient = false;
        init.jwsAudiences = Optional.of(List.of());
        init.extVerify = new EmptyExtVerify();
        return init;
    }

    @AfterEach
    void resetSharedStatic() {
        // ServerContext.credentialVerify is a STATIC field; a leaked registration would poison
        // later tests / files. Reset unconditionally.
        ServerContext.regCredentialVerify(null);
    }

    @Test
    void failedJwksLoad_registersFailClosedVerifier_notFailOpen() throws IOException {
        var init = failingInit(refusedUrl());

        // scheme (a): startup stays up on JWKS failure — init() must NOT throw.
        assertDoesNotThrow(init::init,
                "init() must survive a JWKS load failure (fail-closed, not startup-abort)");

        // (a) The verifier IS registered. Pre-fix, a load failure left credentialVerify == null,
        // so ServerContext.verify() skipped the credential check → auth silently disabled.
        var verifier = ServerContext.credentialVerify();
        assertNotNull(verifier,
                "verifier must be registered even when JWKS load failed (null == fail-open bug)");

        // (b) ...and it REJECTS. Registered-but-rejecting is the whole point: JWKS never loaded ⇒
        // not ready ⇒ every credentialed request is refused with UNAVAILABLE (not UNAUTHENTICATED,
        // so ops can distinguish "auth backend not ready" from "bad token").
        StatusException thrown = assertThrows(StatusException.class,
                () -> verifier.verify("a.b.c", "cid", false),
                "fail-closed verifier must reject, not admit, while JWKS is unloaded");
        assertEquals(Status.Code.UNAVAILABLE, thrown.getStatus().getCode(),
                () -> "expected UNAVAILABLE from fail-closed gate, got " + thrown.getStatus().getCode());
    }

    @Test
    void exitOnJwksError_true_abortsStartup() throws IOException {
        var init = failingInit(refusedUrl());
        init.exitOnJwksError = true;

        // scheme (b): loud startup abort — init() must rethrow the load failure.
        assertThrows(RuntimeException.class, init::init,
                "with exitOnJwksError=true, a JWKS load failure must abort startup (rethrow)");
    }

    @Test
    void noJwksUrl_skips() {
        var init = new InitJwsVerify();
        init.jwks = Optional.empty();
        init.jwsCookie = JwsVerify.DEFAULT_COOKIE_NAME;
        init.exitOnJwksError = false;
        init.bindClient = false;
        init.jwsAudiences = Optional.of(List.of());
        init.extVerify = new EmptyExtVerify();

        // No JWKS URL configured ⇒ init() is a no-op: no throw, no verifier registered.
        assertDoesNotThrow(init::init, "init() must be a no-op when no JWKS URL is configured");
        assertNull(ServerContext.credentialVerify(),
                "no JWKS URL configured ⇒ no verifier should be registered");
    }
}
