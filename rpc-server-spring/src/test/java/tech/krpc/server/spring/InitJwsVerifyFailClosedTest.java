/**
 * Martin.Cong
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.server.spring;

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
 * HARDEN-B1 fail-open regression guard for the SPRING wiring module.
 *
 * <p>InitJwsVerify.init() delegates to JwsVerify.bootstrapAndRegister(...). The invariant under
 * test: when the JWKS URL is configured but the load FAILS at init, the framework wiring must leave
 * auth FAIL-CLOSED — the verifier is REGISTERED and REJECTS every credentialed request — never
 * fail-open. Pre-fix, init() caught the load error and returned WITHOUT registering, so
 * ServerContext.credentialVerify stayed null and ServerContext.checkCredential() skipped auth
 * entirely (silent fail-open). Registered-but-rejecting (UNAVAILABLE) is the fix.
 *
 * <p>Plain JUnit5 — no Spring container is booted. InitJwsVerify is instantiated directly and its
 * package-private / lombok-@Setter fields are populated from this same-package test.
 */
class InitJwsVerifyFailClosedTest {

    /**
     * A URL pointing at a port that is guaranteed closed: bind an ephemeral port, record it, then
     * release it on exit of the try-with-resources. A connection there is refused, so the JWKS
     * fetch fails deterministically without depending on network conditions.
     */
    private static String refusedUrl() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return "http://127.0.0.1:" + ss.getLocalPort() + "/";
        }
    }

    @AfterEach
    void resetRegisteredVerifier() {
        // MUST reset the shared STATIC verifier so a registration from one test cannot bleed into
        // the next (or another test class). See harden-b1-test-contract.md reset rule.
        ServerContext.regCredentialVerify(null);
    }

    @Test
    void failedJwksLoad_registersFailClosedVerifier_notFailOpen() throws Exception {
        var init = new InitJwsVerify();
        init.setJwks(Optional.of(refusedUrl()));
        init.setJwsCookie(JwsVerify.DEFAULT_COOKIE_NAME);
        init.setExitOnJwksError(false);
        init.setBindClient(false);
        init.setJwsAudiences(List.of());
        init.extVerify = new EmptyExtVerify();

        // scheme (a): a JWKS load failure with exitOnJwksError=false must NOT abort startup.
        assertDoesNotThrow(init::init, "init() must stay up (fail-closed), not abort, on JWKS load failure");

        // (a) the verifier IS registered. Pre-fix, init() caught the load error and returned WITHOUT
        // registering → ServerContext.credentialVerify stayed null → checkCredential() skipped auth
        // (fail-open). A non-null verifier here is half of the anti-fail-open contract.
        assertNotNull(ServerContext.credentialVerify(),
                "verifier MUST be registered even when JWKS load fails — null == the fail-open bug");

        // (b) and it REJECTS. JWKS never loaded ⇒ not ready ⇒ UNAVAILABLE (distinct from a bad
        // token's UNAUTHENTICATED), so ops can tell "auth backend down" from "caller sent garbage".
        var ex = assertThrows(StatusException.class,
                () -> ServerContext.credentialVerify().verify("a.b.c", "cid", false),
                "a registered-but-not-ready verifier MUST reject, never admit the request");
        assertEquals(Status.Code.UNAVAILABLE, ex.getStatus().getCode(),
                () -> "fail-closed rejection MUST be UNAVAILABLE, was " + ex.getStatus().getCode());
    }

    @Test
    void exitOnJwksError_true_abortsStartup() throws Exception {
        var init = new InitJwsVerify();
        init.setJwks(Optional.of(refusedUrl()));
        init.setJwsCookie(JwsVerify.DEFAULT_COOKIE_NAME);
        init.setExitOnJwksError(true);
        init.setBindClient(false);
        init.setJwsAudiences(List.of());
        init.extVerify = new EmptyExtVerify();

        // scheme (b): exitOnJwksError=true rethrows the load failure so startup aborts loudly
        // rather than silently coming up without a working auth backend.
        assertThrows(RuntimeException.class, init::init,
                "exitOnJwksError=true MUST abort startup (rethrow) on JWKS load failure");
    }

    @Test
    void noJwksUrl_skips() {
        var init = new InitJwsVerify();
        init.setJwks(Optional.empty());

        // No JWKS URL configured = the auth feature is legitimately OFF. init() must skip cleanly
        // and register nothing — this is the ONLY sanctioned path that leaves credentialVerify null.
        assertDoesNotThrow(init::init, "init() must skip cleanly when no JWKS URL is set");
        assertNull(ServerContext.credentialVerify(),
                "no JWKS URL configured = feature off ⇒ no verifier registered");
    }
}
