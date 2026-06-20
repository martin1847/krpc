/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package tech.krpc.client;

//import jakarta.validation.constraints.NotNull;

import tech.krpc.context.TraceMeta;
import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import org.slf4j.MDC;

/**
 * ADR-0003: forwards the inbound W3C trace context (traceparent + tracestate) and
 * x-request-id to the outbound call. Pure propagation, no span creation.
 *
 * @author martin.cong
 * @version 2022-01-06 22:33
 */
class PropagateTraceCall extends ForwardingClientCall<InputProto, OutputProto> {

    final ClientCall<InputProto, OutputProto> delegate;
    final String                              traceparent, tracestate, requestId;

    PropagateTraceCall(ClientCall<InputProto, OutputProto> delegate,
                       //@NotNull
        String traceparent) {
        this.delegate = delegate;
        this.traceparent = traceparent;
        tracestate = MDC.get(TraceMeta.TRACESTATE);
        requestId = MDC.get(TraceMeta.X_REQUEST_ID);
    }

    @Override
    public void start(Listener<OutputProto> responseListener, Metadata headers) {
        headers.put(TraceMeta.TRACEPARENT_KEY, traceparent);
        if (null != tracestate) {
            headers.put(TraceMeta.TRACESTATE_KEY, tracestate);
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