package tech.krpc.server;

import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import tech.krpc.context.KrpcOtel;

/**
 * ADR-0006: gRPC SERVER interceptor. Extracts the inbound W3C trace context from the call
 * metadata, starts a SERVER span (child of the extracted remote span), and makes that span current
 * on the thread that runs each listener callback — including {@code onHalfClose}, where krpc
 * executes the handler on the app executor (a virtual thread; ADR-0002). So the handler and any
 * outbound client call it makes see the SERVER span as their current context, keeping the trace
 * connected across the hop.
 *
 * <p>OpenTelemetry API only (no SDK — ADR-0001 / NS-3). Until an SDK is installed
 * ({@link KrpcOtel#install}), {@code isNoop()} is true and this interceptor is a pass-through —
 * zero spans, zero wire change (NS-4). ADR-0003's MDC forwarding (parsed in
 * {@link ServerContext}) is untouched and continues in parallel.
 */
public final class OtelServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        // B1 (ADR-0006): no OTel SDK -> no-op TracerProvider. Skip all span-creation work and the
        // listener/scope wrapping; the call proceeds untouched (zero allocation beyond this check).
        if (KrpcOtel.isNoop()) {
            return next.startCall(call, headers);
        }

        var descriptor = call.getMethodDescriptor();
        Context parent = KrpcOtel.propagator()
                .extract(Context.current(), headers, KrpcOtel.METADATA_GETTER);

        Span span = KrpcOtel.tracer().spanBuilder(descriptor.getFullMethodName())
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .setAttribute(KrpcOtel.RPC_SYSTEM, KrpcOtel.RPC_SYSTEM_GRPC)
                .setAttribute(KrpcOtel.RPC_SERVICE, nullToEmpty(descriptor.getServiceName()))
                .setAttribute(KrpcOtel.RPC_METHOD, nullToEmpty(descriptor.getBareMethodName()))
                .startSpan();

        Context ctx = parent.with(span);
        AtomicBoolean ended = new AtomicBoolean(false);

        // Record the gRPC status on close; span ends on the terminal listener event (below).
        var tracedCall = new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                span.setAttribute(KrpcOtel.RPC_GRPC_STATUS_CODE, (long) status.getCode().value());
                if (!status.isOk()) {
                    span.setStatus(StatusCode.ERROR, status.getCode().name());
                    if (status.getCause() != null) {
                        span.recordException(status.getCause());
                    }
                }
                super.close(status, trailers);
            }
        };

        ServerCall.Listener<ReqT> listener;
        try (Scope scope = ctx.makeCurrent()) {
            listener = next.startCall(tracedCall, headers);
        }
        return new TracingServerCallListener<>(listener, ctx, span, ended);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Makes the SERVER-span context current for every listener callback — the callbacks run on the
     * app executor, so this is what carries the span onto the handler's virtual thread — and ends
     * the span exactly once on the terminal event ({@code onComplete} or {@code onCancel}).
     */
    private static final class TracingServerCallListener<ReqT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final Context ctx;
        private final Span span;
        private final AtomicBoolean ended;

        TracingServerCallListener(ServerCall.Listener<ReqT> delegate, Context ctx, Span span,
                                  AtomicBoolean ended) {
            super(delegate);
            this.ctx = ctx;
            this.span = span;
            this.ended = ended;
        }

        @Override
        public void onMessage(ReqT message) {
            try (Scope scope = ctx.makeCurrent()) {
                super.onMessage(message);
            }
        }

        @Override
        public void onHalfClose() {
            // krpc runs the handler here (on the app executor); the span must be current.
            try (Scope scope = ctx.makeCurrent()) {
                super.onHalfClose();
            }
        }

        @Override
        public void onReady() {
            try (Scope scope = ctx.makeCurrent()) {
                super.onReady();
            }
        }

        @Override
        public void onComplete() {
            try (Scope scope = ctx.makeCurrent()) {
                super.onComplete();
            } finally {
                endOnce();
            }
        }

        @Override
        public void onCancel() {
            try (Scope scope = ctx.makeCurrent()) {
                super.onCancel();
            } finally {
                if (ended.compareAndSet(false, true)) {
                    span.setStatus(StatusCode.ERROR, Status.Code.CANCELLED.name());
                    span.end();
                }
            }
        }

        private void endOnce() {
            if (ended.compareAndSet(false, true)) {
                span.end();
            }
        }
    }
}
