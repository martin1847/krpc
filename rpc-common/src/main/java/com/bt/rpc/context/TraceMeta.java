/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.context;

import io.grpc.Metadata;
import io.grpc.Metadata.Key;

/**
 *
 * @author martin.cong
 * @version 2022-01-06 21:11
 */
public interface TraceMeta {

    // https://github.com/openzipkin/b3-propagation#grpc-encoding
    String X_REQUEST_ID = "x-request-id";
    String X_B3_TRACE_ID = "x-b3-traceid";
    String X_B3_SPAN_ID = "x-b3-spanid";
    String X_B3_PARENT_SPAN_ID = "x-b3-parentspanid";
    String X_B3_SAMPLED = "x-b3-sampled";

    /**
     * Debug Flag
     * Debug is encoded as X-B3-Flags: 1.
     * Absent or any other value can be ignored.
     * Debug implies an accept decision, so don't also send  the X-B3-Sampled header.
     */
    String X_B3_DEBUG_FLAG = "x-b3-flags";

    Key<String> REQUEST_ID   = Metadata.Key.of(X_REQUEST_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> TRACE_ID   = Metadata.Key.of(X_B3_TRACE_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> SPAN_ID    = Metadata.Key.of(X_B3_SPAN_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> PARENT_SPAN_ID    = Metadata.Key.of(X_B3_PARENT_SPAN_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> SAMPLED   = Metadata.Key.of(X_B3_SAMPLED, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> DEBUG_FLAG   = Metadata.Key.of(X_B3_DEBUG_FLAG, Metadata.ASCII_STRING_MARSHALLER);
}