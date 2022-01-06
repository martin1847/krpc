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

    String X_REQUEST_ID = "x-request-id";
    String X_B3_TRACE_ID = "x-b3-traceid";
    String X_B3_SPAN_ID = "x-b3-spanid";
    String X_B3_PARENT_SPAN_ID = "x-b3-parentspanid";
    String X_B3_SAMPLED = "x-b3-sampled";

    Key<String> REQUEST_ID   = Metadata.Key.of(X_REQUEST_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> TRACE_ID   = Metadata.Key.of(X_B3_TRACE_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> SPAN_ID    = Metadata.Key.of(X_B3_SPAN_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> PARENT_SPAN_ID    = Metadata.Key.of(X_B3_PARENT_SPAN_ID, Metadata.ASCII_STRING_MARSHALLER);
    Key<String> SAMPLED   = Metadata.Key.of(X_B3_SAMPLED, Metadata.ASCII_STRING_MARSHALLER);
}