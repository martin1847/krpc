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
    void defaultHelperWithLegalSig_isRegistered_noThrow() {
        // A default method with a legal endpoint signature is not an endpoint declaration, so it is
        // never signature-checked — and being RpcResult<..>/≤1-param it still lands in the list.
        List<MethodStub> stubs = assertDoesNotThrow(
                () -> RefUtils.toRpcMethods("app", DefaultHelperLegalService.class));
        assertTrue(containsMethod(stubs, "real"), "the real endpoint must be registered");
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
