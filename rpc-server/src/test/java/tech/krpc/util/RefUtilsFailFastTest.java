package tech.krpc.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tech.krpc.annotation.RpcService;
import tech.krpc.common.MethodStub;
import tech.krpc.model.RpcResult;

/**
 * HARDEN-B3 fix #5 — {@link RefUtils#toRpcMethods} fail-fast on a mis-declared RPC endpoint.
 *
 * <p>Pre-fix, a method whose return was not {@code RpcResult<…>} or which took &gt;1 param was
 * silently FILTERED OUT: the service started clean, the endpoint simply vanished, and calls failed to
 * resolve only at runtime. The contract now: an ABSTRACT public interface method (a genuine endpoint
 * declaration) with an illegal signature is a hard {@link IllegalStateException} at discovery, while
 * {@code default}/{@code static} methods (the sanctioned helper escape hatch) are EXEMPT even when
 * their signature would be illegal for an endpoint.
 */
class RefUtilsFailFastTest {

    /** A DTO param type for a legal 1-arg helper (mirrors the real {@code DemoService.saveImg}). */
    static class Img {
    }

    @RpcService
    interface LegalService {
        RpcResult<String> a(String s);

        RpcResult<String> b();
    }

    @RpcService
    interface BadReturnService {
        String bad();
    }

    @RpcService
    interface TwoParamService {
        RpcResult<String> bad(String a, String b);
    }

    @RpcService
    interface DefaultHelperLegalService {
        RpcResult<String> real();

        default RpcResult<Integer> helper(Img i) {
            return null;
        }
    }

    @RpcService
    interface DefaultHelperIllegalService {
        RpcResult<String> real();

        default String helper() {
            return null;
        }
    }

    @RpcService
    interface StaticHelperIllegalService {
        RpcResult<String> real();

        static String helper() {
            return null;
        }
    }

    @RpcService
    interface StaticHelperLegalService {
        RpcResult<String> real();

        // legal ENDPOINT signature (RpcResult return, 0 params) but static ⇒ a helper, not an endpoint.
        static RpcResult<String> helper() {
            return null;
        }
    }

    @Test
    void legalService_registersEndpoints_noThrow() {
        List<MethodStub> stubs = RefUtils.toRpcMethods("app", LegalService.class);
        assertFalse(stubs.isEmpty(), "a legal @RpcService must register its endpoints");
    }

    @Test
    void abstractIllegalReturn_failsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> RefUtils.toRpcMethods("app", BadReturnService.class));
        assertTrue(ex.getMessage().contains("bad"),
                () -> "message must name the offending method 'bad', got: " + ex.getMessage());
    }

    @Test
    void abstractTwoParam_failsFast() {
        var ex = assertThrows(IllegalStateException.class,
                () -> RefUtils.toRpcMethods("app", TwoParamService.class));
        assertTrue(ex.getMessage().contains("bad"),
                () -> "message must name the offending method 'bad', got: " + ex.getMessage());
    }

    @Test
    void defaultHelperWithLegalSig_isNotRegistered() {
        // C9 (fix-round-1): a default method is a HELPER (carries a body), never an endpoint — even
        // with a legal endpoint signature. It must NOT be registered (pre-fix it slipped in because
        // the filter kept any RpcResult<..>/≤1-param method). The real endpoint still registers.
        List<MethodStub> stubs = assertDoesNotThrow(
                () -> RefUtils.toRpcMethods("app", DefaultHelperLegalService.class));
        assertTrue(containsMethod(stubs, "real"), "the real endpoint must be registered");
        assertFalse(containsMethod(stubs, "helper"),
                "a default helper (legal sig or not) must NOT be registered as an endpoint");
    }

    @Test
    void staticHelperWithLegalSig_isNotRegistered() {
        // C9 (fix-round-1) RED LINE: a legal-signature `static RpcResult<T> helper()` passed the
        // illegal-check (static ⇒ exempt) AND the old registration filter (RpcResult, 0 params), so it
        // was registered as a phantom endpoint. It must NOT be — static methods are helpers, not RPCs.
        List<MethodStub> stubs = assertDoesNotThrow(
                () -> RefUtils.toRpcMethods("app", StaticHelperLegalService.class));
        assertTrue(containsMethod(stubs, "real"), "the real endpoint must be registered");
        assertFalse(containsMethod(stubs, "helper"),
                "a legal-signature static helper must NOT be registered as an endpoint");
    }

    @Test
    void defaultHelperWithIllegalSig_isExempt_realStillRegistered() {
        // default methods are EXEMPT from the endpoint check even when illegal (String return here).
        List<MethodStub> stubs = assertDoesNotThrow(
                () -> RefUtils.toRpcMethods("app", DefaultHelperIllegalService.class));
        assertTrue(containsMethod(stubs, "real"), "the real endpoint must be registered");
    }

    @Test
    void staticHelperWithIllegalSig_isExempt_realStillRegistered() {
        // static methods are likewise exempt from the endpoint signature check.
        List<MethodStub> stubs = assertDoesNotThrow(
                () -> RefUtils.toRpcMethods("app", StaticHelperIllegalService.class));
        assertTrue(containsMethod(stubs, "real"), "the real endpoint must be registered");
    }

    private static boolean containsMethod(List<MethodStub> stubs, String name) {
        return stubs.stream().anyMatch(s -> s.method.getName().equals(name));
    }
}
