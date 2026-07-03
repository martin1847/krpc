package tech.krpc.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * HARDEN-B3 fix #1 — {@link RpcResult} envelope invariants + non-aliasing {@code ifOk}.
 *
 * <p>Each test defends ONE externally observable contract of the DTO envelope:
 * <ul>
 *   <li>the constructor invariants ({@code ok} needs non-null data, {@code error} needs code&gt;0 and
 *       non-null msg) are enforced UNCONDITIONALLY — pre-fix they rode on {@code assert}, a no-op
 *       under production {@code -da}, so a null/zero-code envelope escaped silently;</li>
 *   <li>{@code error()} may only forward a real failure, and must reject an OK result;</li>
 *   <li><b>the #1 RED LINE</b>: {@code ifOk} on an OK result returns a NEW object and never mutates
 *       its receiver — pre-fix it re-typed {@code this} and swapped {@code data}, so any caller still
 *       holding the original reference silently saw its DTO replaced (aliasing).</li>
 * </ul>
 */
class RpcResultContractTest {

    @Test
    void ok_rejectsNullData() {
        // code==0 ⇒ data present. Enforced unconditionally (was a -da assert pre-fix).
        assertThrows(NullPointerException.class, () -> RpcResult.ok(null));
    }

    @Test
    void error_rejectsNonPositiveCode() {
        // 0 is OK, negatives are unsupported — an "error" with code<=0 is a programming error.
        assertThrows(IllegalArgumentException.class, () -> RpcResult.error(0, "x"));
        assertThrows(IllegalArgumentException.class, () -> RpcResult.error(-1, "x"));
    }

    @Test
    void error_rejectsNullMsg() {
        assertThrows(NullPointerException.class, () -> RpcResult.error(5, null));
    }

    @Test
    void error_forwardGuard_rejectsOkResult() {
        // error() on an OK result carries no error to forward — a silent contract break pre-fix.
        RpcResult<String> ok = RpcResult.ok("d");
        assertThrows(IllegalStateException.class, ok::error);
    }

    @Test
    void error_forwardGuard_allowsFailure_preservesCode() {
        RpcResult<Integer> forwarded = RpcResult.<String>error(7, "e").error();
        assertEquals(7, forwarded.getCode());
    }

    @Test
    void ifOk_onOk_returnsNewObject_andDoesNotMutateReceiver() {
        // THE #1 red line. Pre-fix ifOk did `((RpcResult<T>)this).data = res; return this`, so
        // `orig` (still String-typed to its holder) had its data field overwritten with 5.
        RpcResult<String> orig = RpcResult.ok("hello");
        RpcResult<Integer> mapped = orig.ifOk(String::length);

        assertNotSame(orig, mapped);               // a NEW object, not the mutated receiver
        assertEquals("hello", orig.getData());     // original DTO untouched (pre-fix: became 5)
        assertEquals(0, orig.getCode());
        assertEquals(Integer.valueOf(5), mapped.getData());
    }

    @Test
    void ifOk_onFailure_returnsSameFailure_andSkipsFn() {
        RpcResult<String> failure = RpcResult.error(9, "boom");
        boolean[] ran = {false};
        RpcResult<Integer> mapped = failure.ifOk(s -> {
            ran[0] = true;
            return s.length();
        });

        assertSame(failure, mapped);       // the failure is forwarded verbatim
        assertEquals(9, mapped.getCode());
        assertEquals(false, ran[0]);       // fn never runs on the failure branch
    }

    @Test
    void ifOk_fnReturningNull_yieldsDataLoss() {
        RpcResult<String> ok = RpcResult.ok("x");
        RpcResult<Object> mapped = ok.ifOk(s -> null);

        assertEquals(RpcResult.DATA_LOSS, mapped.getCode());
        assertEquals(15, RpcResult.DATA_LOSS);
    }
}
