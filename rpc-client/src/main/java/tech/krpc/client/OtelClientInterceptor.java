package tech.krpc.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import tech.krpc.context.KrpcOtel;

/**
 * ADR-0006: gRPC CLIENT interceptor. Starts a CLIENT span (child of the current context — the
 * SERVER span when the call originates inside a handler) and injects W3C {@code traceparent} into
 * the outbound metadata, so the downstream server continues the same trace.
 *
 * <p>Uses the OpenTelemetry API only (no SDK — ADR-0001 / NS-3). Until an SDK is installed
 * ({@link KrpcOtel#install}), {@code isNoop()} is true and this interceptor is a pass-through: no
 * span, no injection, and the ADR-0003 MDC-forwarded {@code traceparent} survives unchanged — one
 * header, byte-identical wire (NS-4). With an SDK installed, {@link KrpcOtel#METADATA_SETTER}
 * overwrites, so the client-span {@code traceparent} supersedes the MDC-forwarded parent.
 */
public final class OtelClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        // B1 (ADR-0006): no OTel SDK -> no-op TracerProvider. Return the raw call unwrapped (no
        // span, no injection); ADR-0003 MDC forwarding still applies. Zero allocation beyond the check.
        if (KrpcOtel.isNoop()) {
            return next.newCall(method, callOptions);
        }
        return new TracingClientCall<>(next.newCall(method, callOptions), method);
    }

    private static final class TracingClientCall<ReqT, RespT>
            extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

        private final MethodDescriptor<ReqT, RespT> method;
        private Span span;

        TracingClientCall(ClientCall<ReqT, RespT> delegate, MethodDescriptor<ReqT, RespT> method) {
            super(delegate);
            this.method = method;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            span = KrpcOtel.tracer().spanBuilder(method.getFullMethodName())
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(KrpcOtel.RPC_SYSTEM, KrpcOtel.RPC_SYSTEM_GRPC)
                    .setAttribute(KrpcOtel.RPC_SERVICE, nullToEmpty(method.getServiceName()))
                    .setAttribute(KrpcOtel.RPC_METHOD, nullToEmpty(method.getBareMethodName()))
                    .startSpan();
            // Parent is Context.current() (the default): the SERVER span in scope on the handler
            // thread when the call originates inside a handler; Context.root() otherwise.
            Context ctx = Context.current().with(span);
            try (Scope scope = ctx.makeCurrent()) {
                // Inject with the client-span context so the emitted traceparent reflects this span.
                KrpcOtel.propagator()
                        .inject(ctx, headers, KrpcOtel.METADATA_SETTER);
                super.start(new TracingClientCallListener<>(responseListener, span), headers);
            }
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }
    }

    private static final class TracingClientCallListener<RespT>
            extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {

        private final Span span;

        TracingClientCallListener(ClientCall.Listener<RespT> delegate, Span span) {
            super(delegate);
            this.span = span;
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            span.setAttribute(KrpcOtel.RPC_GRPC_STATUS_CODE, (long) status.getCode().value());
            if (!status.isOk()) {
                span.setStatus(StatusCode.ERROR, status.getCode().name());
                if (status.getCause() != null) {
                    span.recordException(status.getCause());
                }
            }
            span.end();
            super.onClose(status, trailers);
        }
    }
}
