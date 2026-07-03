package tech.krpc.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.krpc.server.jws.CredentialVerify;
import tech.krpc.server.jws.UserCredential;

/**
 * HARDEN-B3 fix #7 — {@link ServerContext#checkCredential()} fails CLOSED: {@code verifyed} flips
 * true only AFTER a clean verify.
 *
 * <p>Package {@code tech.krpc.server} on purpose ({@code checkCredential} is package-private;
 * {@code uid}/{@code softUid} are public). The registered verifier is a global static, so it is set
 * in {@code @BeforeEach} and RESET to {@code null} in {@code @AfterEach} — leaving it registered
 * would poison every later test.
 *
 * <p>Pre-fix, {@code verifyed} was set BEFORE {@code verify()} ran, so a throwing {@code verify()}
 * left {@code verifyed==true} + {@code credential==null}: {@code softUid()} then short-circuited on
 * the stale flag and {@code uid()} NPE'd on the null credential. The observable proofs here are that
 * {@code verify()} runs on EVERY {@code softUid()} (the flag never stuck) and that {@code uid()}
 * rejects cleanly with {@link IllegalStateException}, not an NPE.
 */
class ServerContextCredentialTest {

    /** A verifier that always REJECTS and counts how many times {@code verify()} was invoked. */
    static final class RejectingVerify implements CredentialVerify {
        int verifyCalls;

        @Override
        public UserCredential verify(String token, String cid, boolean isCookie)
                throws StatusException {
            verifyCalls++;
            throw Status.UNAUTHENTICATED.asException();
        }

        @Override
        public String getCookieName() {
            return "access-token";
        }
    }

    private RejectingVerify fake;

    @BeforeEach
    void setUp() {
        fake = new RejectingVerify();
        ServerContext.regCredentialVerify(fake);
    }

    @AfterEach
    void tearDown() {
        // MUST reset the static global — a lingering verifier would fail-close every later test.
        ServerContext.regCredentialVerify(null);
    }

    private static ServerContext newContext() {
        return new ServerContext(null, "m", null, null, null, new Metadata());
    }

    @Test
    void softUid_swallowsRejection_returnsNull() {
        var ctx = newContext();
        assertNull(ctx.softUid(), "softUid must swallow the StatusException and return null");
    }

    @Test
    void uid_afterFailedVerify_throwsIllegalState_notNpe() {
        var ctx = newContext();
        ctx.softUid(); // triggers the failing verify; credential stays null
        // Clean rejection, NOT an NPE — pre-fix verifyed flipped true before verify threw, leaving
        // credential null, and uid() dereferenced it.
        assertThrows(IllegalStateException.class, ctx::uid);
    }

    @Test
    void verifyedStaysFalseOnFailure_verifyRunsEveryTime() {
        var ctx = newContext();
        ctx.softUid();
        ctx.softUid();
        // The observable proof the flag only flips after a CLEAN verify: two softUid() calls ⇒ two
        // verify() invocations. Pre-fix it would be 1 (the stale flag short-circuited the 2nd).
        assertEquals(2, fake.verifyCalls);
    }
}
