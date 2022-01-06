/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.bt.rpc.client;

import javax.validation.constraints.NotNull;

import com.bt.rpc.context.TraceMeta;
import com.bt.rpc.internal.InputProto;
import com.bt.rpc.internal.OutputProto;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import org.slf4j.MDC;

/**
 *
 * @author martin.cong
 * @version 2022-01-06 22:33
 */
class PropagateTraceCall extends ForwardingClientCall<InputProto, OutputProto> {

    final ClientCall<InputProto, OutputProto> delegate;
    final String                              traceId, spanId, parentSpanId, sampled, requestId;

    PropagateTraceCall(ClientCall<InputProto, OutputProto> delegate,@NotNull String traceId) {
        this.delegate = delegate;
        this.traceId = traceId;
        spanId = MDC.get(TraceMeta.X_B3_SPAN_ID);
        parentSpanId = MDC.get(TraceMeta.X_B3_PARENT_SPAN_ID);
        sampled = MDC.get(TraceMeta.X_B3_SAMPLED);
        requestId = MDC.get(TraceMeta.X_REQUEST_ID);
    }

    @Override
    public void start(Listener<OutputProto> responseListener, Metadata headers) {
        headers.put(TraceMeta.TRACE_ID, traceId);
        if (null != spanId) {
            headers.put(TraceMeta.SPAN_ID, spanId);
        }
        if (null != parentSpanId) {
            headers.put(TraceMeta.PARENT_SPAN_ID, parentSpanId);
        }
        if (null != sampled) {
            headers.put(TraceMeta.SAMPLED, sampled);
        }
        if (null != requestId) {
            headers.put(TraceMeta.REQUEST_ID, requestId);
        }
        super.start(responseListener, headers);
    }

    @Override
    protected ClientCall<InputProto, OutputProto> delegate() {
        return delegate;
    }
}