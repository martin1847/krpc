/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.context;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;

/**
 * ADR-0003: trace propagation uses W3C Trace Context (traceparent single header),
 * not B3 multi-header. The framework only forwards the inbound trace context to the
 * outbound call; it does not create spans.
 *
 * @author martin.cong
 * @version 2022-01-06 21:11
 */
public interface TraceMeta {

    // https://www.w3.org/TR/trace-context/
    String X_REQUEST_ID = "x-request-id";
    String TRACEPARENT  = "traceparent";
    String TRACESTATE   = "tracestate";

    // MDC keys: traceparent is propagated verbatim; traceId/spanId are derived for log layout.
    String MDC_TRACEPARENT = "traceparent";
    String MDC_TRACE_ID    = "traceId";
    String MDC_SPAN_ID     = "spanId";

    Key<String> REQUEST_ID   = Metadata.Key.of(X_REQUEST_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> TRACEPARENT_KEY = Metadata.Key.of(TRACEPARENT, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> TRACESTATE_KEY  = Metadata.Key.of(TRACESTATE, Metadata.ASCII_STRING_MARSHALLER);

    /**
     * ADR-0003 / OTEL-002 R1-4 + R2-1: parse and validate a W3C traceparent header.
     * Format: {@code version(2hex)-traceid(32hex)-spanid(16hex)-flags(2hex)}, all lower-case hex.
     * Returns {@code [traceId, spanId]} only when the four common fields are valid: correct
     * lengths, lower-case hex throughout, version not {@code ff} (reserved), and neither the
     * trace-id nor span-id all-zero (both forbidden). Version {@code 00} must be exactly four
     * fields; a higher (non-{@code ff}) version MAY carry opaque trailing version-specific fields,
     * which are accepted and left to the caller to forward verbatim (W3C forward-compatibility).
     * Any malformed common field → null, so a caller never forwards/logs an incoherent context.
     */
    static String[] parse(String traceparent) {
        if (null == traceparent) {
            return null;
        }
        var parts = traceparent.split("-", -1);
        // Four common W3C fields are mandatory for every version; a future (non-ff) version MAY add
        // opaque trailing fields after them (R2-1 forward-compat) — validate the four, keep the rest.
        if (parts.length < 4
                || parts[0].length() != 2 || !isLowerHex(parts[0]) || "ff".equals(parts[0])
                || parts[1].length() != 32 || !isLowerHex(parts[1]) || isAllZero(parts[1])
                || parts[2].length() != 16 || !isLowerHex(parts[2]) || isAllZero(parts[2])
                || parts[3].length() != 2 || !isLowerHex(parts[3])) {
            return null;
        }
        // Version 00 is exactly four fields — any trailing segment makes it invalid. Higher versions
        // tolerate a trailing version-specific portion (treated as opaque; the header is forwarded
        // verbatim by the caller).
        if ("00".equals(parts[0]) && parts.length != 4) {
            return null;
        }
        return new String[]{parts[1], parts[2]};
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZero(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
