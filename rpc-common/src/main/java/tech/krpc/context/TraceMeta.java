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
     * ADR-0003: parse a W3C traceparent header.
     * Format: {@code version-traceid(32hex)-spanid(16hex)-flags(2hex)}.
     * Returns {@code [traceId, spanId]}, or null when the input is null or malformed.
     */
    static String[] parse(String traceparent) {
        if (null == traceparent) {
            return null;
        }
        var parts = traceparent.split("-");
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16 || parts[3].length() != 2) {
            return null;
        }
        return new String[]{parts[1], parts[2]};
    }
}
