/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.http.server;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.handler.timeout.IdleStateEvent;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tech.krpc.util.JsonUtils;
import tech.krpc.util.JsonDecodeException;
import tech.krpc.context.KrpcOtel;
import tech.krpc.context.TraceMeta;
import tech.krpc.util.LogRedact;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

/**
 *
 * @author Martin.C
 * @version 2021/11/12 5:12 PM
 */
//@ApplicationScoped
@Sharable
public abstract class AbstractHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final Logger log = LoggerFactory.getLogger(AbstractHttpHandler.class);

    public static final String TYPE_PLAIN  = "text/plain; charset=UTF-8";
    public static final String TYPE_JSON   = "application/json; charset=UTF-8";
    public static final String SERVER_NAME = "Netty";

    // ADR-0004 (AGENT-001 P1): a handler may set this response header to override the
    // default HTTP 200 (e.g. MCP notifications -> 202). It is consumed during response dispatch
    // and never written to the wire.
    public static final String STATUS_OVERRIDE_HEADER = "x-krpc-http-status";

    protected final Map<String, PostHandler> postMap = new HashMap<>();

    protected final Map<String, GetHandler> getHanlderMap = new HashMap<>();

    // O6 + AUD-omp-09: business logic must NOT run on the netty NIO worker eventLoop — a blocking
    // handler (DB / downstream call) there starves the whole front door (workerGroup is bounded).
    // Each request's handle() is dispatched to a virtual thread; the response write is scheduled
    // back onto the channel's eventLoop (netty threading model: writes belong to the eventLoop,
    // never a foreign thread). Virtual threads are daemon, so no explicit shutdown is needed.
    static final ExecutorService HANDLER_VT = Executors.newVirtualThreadPerTaskExecutor();

    // ADR-0003 requirement 4 (known debt 2, fixed): the OTEL kill switch has exactly ONE resolution
    // point — KrpcOtel.enabled(). This class used to capture it into a `private static final boolean`
    // for JIT folding; that capture resolves at class-init, i.e. at image BUILD time under
    // GraalVM/Quarkus, while the accessor now resolves at runtime — so one KRPC_OTEL=false would be
    // honoured on the gRPC face and ignored on this HTTP face of the same binary. The accessor is
    // therefore called per request (a volatile read once resolved); caching is its business, not ours.

    // W3C context extraction from inbound Netty HTTP headers (webhook/callback entry).
    private static final TextMapGetter<HttpHeaders> HTTP_HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpHeaders carrier) {
            return carrier == null ? java.util.List.of() : carrier.names();
        }
        @Override
        public String get(HttpHeaders carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    // O6 (HARDEN-B4 fix round 1): per-connection response-ordering state. The handler is @Sharable,
    // so per-channel state lives in a channel attribute, not an instance field. See ConnState.
    private static final AttributeKey<ConnState> STATE =
            AttributeKey.valueOf(AbstractHttpHandler.class, "connState");

    public abstract Validator getValidator();

    public abstract void initHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {

        if (HttpUtil.is100ContinueExpected(request)) {
            send100Continue(ctx);
        }
        var method = request.method().name();
        var uri = request.uri();

        // OTEL-002 Fix 3: inbound-request visibility for webhook/callback debugging, credential-safe
        // by construction. R1-1: log the raw PATH only — the query string can carry a credential
        // (e.g. ?access_token=...), so it is never logged. Headers/cookies (incl. the access-token
        // JWT cookie, Authorization bearer) are routed through LogRedact — value masked, key kept —
        // so no live token reaches a log line at any level. Consumers cannot patch framework logging,
        // so redaction lives here (redaction doctrine).
        if (log.isDebugEnabled()) {
            log.debug("HTTP inbound {} {} headers={}",
                    method, new QueryStringDecoder(uri).rawPath(), redactHeaders(request.headers()));
        }

        if ("GET".equals(method)) {
            var query = new QueryStringDecoder(uri);
            var handler = getHanlderMap.get(query.rawPath());
            if (null != handler) {
                enqueue(ctx, handlerTask(ctx, handler, query, request.headers()));
                return;
            }
        } else if ("POST".equals(method)) {
            var query = new QueryStringDecoder(uri);
            // var post = postMap.get(uri);
            // post with parameters
            var postHandler = postMap.get(query.rawPath());
            if (null != postHandler) {
                final Object dto;
                try {
                    dto = parsePost(request, postHandler);
                } catch (JsonDecodeException ex) {
                    // AUD-omp-20/omp-21: parsePost's JsonUtils.parse ran BEFORE the dispatch's
                    // try, so a malformed body escaped to exceptionCaught -> ctx.close() -> a bare
                    // connection reset with no HTTP response. Now it is a 400 JSON here. Message is
                    // JsonDecodeException's neutral text; Jackson internals stay in the cause/log.
                    log.warn("bad request body on {}: {}", postHandler.path(), ex.getMessage());
                    enqueue(ctx, errorTask(ctx, HttpResponseStatus.BAD_REQUEST, ex.getMessage()));
                    return;
                } catch (HttpError ex) {
                    // Missing body / validation failure -> 400 (C7 + AUD-omp-21).
                    enqueue(ctx, errorTask(ctx, ex.status, ex.getMessage()));
                    return;
                } catch (RuntimeException ex) {
                    // Any other parse-time failure: neutral 500, never a silent close.
                    log.error("parse error on {}", postHandler.path(), ex);
                    enqueue(ctx, errorTask(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, null));
                    return;
                }

                if (!query.rawQuery().isEmpty() && dto instanceof QueryStringAware) {
                    ((QueryStringAware) dto).setQueryString(query);
                }

                enqueue(ctx, handlerTask(ctx, postHandler, dto, request.headers()));
                return;
            }
        }

        // C7: 404 as a JSON envelope (matching content-type). Echoing the not-found URI discloses
        // nothing sensitive. Queued so it stays in order behind any in-flight request on this conn.
        enqueue(ctx, errorTask(ctx, HttpResponseStatus.NOT_FOUND, uri + " not found"));

    }

    private static <ParamDTO> RequestTask handlerTask(
            ChannelHandlerContext ctx, Handler<ParamDTO> handler, ParamDTO dto, HttpHeaders requestHeaders) {
        // O6 + AUD-omp-09: handle() may block (DB / downstream) so it MUST run off the eventLoop; it
        // is dispatched to a virtual thread and the response write is scheduled back onto the channel
        // eventLoop (writes belong to the eventLoop, never a foreign thread). dto / requestHeaders are
        // already materialized off the request ByteBuf (parsePost / QueryStringDecoder / the
        // String-backed DefaultHttpHeaders), so the VT touches nothing refcounted. onDone (which frees
        // the FIFO slot) fires only AFTER the write is queued on the eventLoop, so the next pipelined
        // request on this connection is dispatched strictly after this response is written.
        return onDone -> HANDLER_VT.execute(() -> {
            HttpResponseStatus status;
            String contentType;
            byte[] bytes;
            List<AsciiHeader> extHeaders = new ArrayList<AsciiHeader>();
            // OTEL-001 (ADR-0006): SERVER span for the handled request, W3C context extracted from
            // the request headers. null when disabled / no-op without an OTel SDK — behaviour and
            // wire stay identical. makeCurrent() puts the span in scope so any outbound client call
            // the handler makes on this virtual thread parents to it.
            // B1 (ADR-0006): skip span creation when disabled OR when no OTel SDK is present
            // (no-op TracerProvider) — no extract, no span, no scope. Per-call check (isNoop) so a
            // late-registered SDK still traces; allocation-free on the no-SDK fast path.
            // OTEL-002 Fix 1 (ADR-0003): bind the inbound W3C trace context into MDC around the
            // handler body — same as the gRPC face (ServerContext). Independent of OTel span
            // creation: it makes handler logs carry traceId/spanId AND lets an outbound krpc client
            // forward the trace via PropagateTraceCall, so a webhook/callback keeps one trace even
            // with no OTel SDK. Cleared in finally (the VT is per-request, but clear is hygiene).
            bindTraceMdc(requestHeaders);
            Span span = (KrpcOtel.enabled() && !KrpcOtel.isNoop())
                    ? startHttpServerSpan(handler, requestHeaders) : null;
            Scope scope = span != null ? span.makeCurrent() : null;
            // OTEL-002 R1-5: once a real SERVER span is current, logging MDC traceId/spanId must
            // identify IT, not the inbound caller's span — so a handler error log joins the span
            // that recorded the error. The inbound traceparent stays in MDC only for the no-SDK
            // legacy forward (PropagateTraceCall); with an SDK the client injector supersedes it.
            if (span != null) {
                var sc = span.getSpanContext();
                MDC.put(TraceMeta.MDC_TRACE_ID, sc.getTraceId());
                MDC.put(TraceMeta.MDC_SPAN_ID, sc.getSpanId());
            }
            try {
                bytes = handler.handle(dto, extHeaders, requestHeaders);
                status = extractStatusOverride(extHeaders);
                contentType = handler.contextType();
                if (span != null) {
                    span.setAttribute(KrpcOtel.HTTP_RESPONSE_STATUS_CODE, (long) status.code());
                }
            } catch (final Throwable ex) {
                // C7 + AUD-omp-21: never echo ex.getMessage() — internal reason stays in the log;
                // client gets a neutral 500 JSON envelope. Catch Throwable so the response AND the
                // FIFO-slot release below are guaranteed even on an Error.
                log.error("handler " + handler.path() + " error", ex);
                status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
                contentType = TYPE_JSON;
                bytes = errorBody(status, null);
                extHeaders = null;
                if (span != null) {
                    span.setAttribute(KrpcOtel.HTTP_RESPONSE_STATUS_CODE, (long) status.code());
                    span.setStatus(StatusCode.ERROR);
                    span.recordException(ex);
                }
            } finally {
                if (scope != null) {
                    scope.close();
                }
                if (span != null) {
                    span.end();
                }
                MDC.clear();
            }
            final HttpResponseStatus fStatus = status;
            final String fContentType = contentType;
            final byte[] fBytes = bytes;
            final List<AsciiHeader> fExtHeaders = extHeaders;
            ctx.channel().eventLoop().execute(() -> {
                try {
                    writeResponse(ctx, fStatus, fContentType, fBytes, fExtHeaders);
                } finally {
                    onDone.run();
                }
            });
        });
    }

    // OTEL-001 (ADR-0006): extract inbound W3C context from the HTTP headers and start a SERVER
    // span named after the handler path. No-op tracer/propagator without an OTel SDK.
    private static Span startHttpServerSpan(Handler<?> handler, HttpHeaders requestHeaders) {
        Context parent = KrpcOtel.propagator()
                .extract(Context.current(), requestHeaders, HTTP_HEADERS_GETTER);
        return KrpcOtel.tracer().spanBuilder(handler.path())
                .setSpanKind(SpanKind.SERVER)
                .setParent(parent)
                .startSpan();
    }

    // OTEL-002 Fix 1 (ADR-0003): mirror ServerContext's inbound-trace MDC binding for the HTTP
    // face, exposing the inbound W3C context to the log layout + PropagateTraceCall.
    //
    // R1-4: forward ONLY a fully valid W3C traceparent. TraceMeta.parse strictly validates
    // (hex/version/flags, non-zero ids); a malformed or absent header sets NO trace MDC keys, so the
    // no-SDK outbound hop carries zero traceparent rather than an incoherent one. The VT starts with
    // empty MDC, so "not set" == cleared.
    //
    // R1-8: this binds MDC + (in the caller) the OTel scope for the SYNCHRONOUS handler body only.
    // Work a handler schedules onto its own executor / CompletableFuture AFTER handle() returns does
    // NOT inherit this MDC or the SERVER-span scope — the app must capture/propagate context itself
    // (e.g. MDC.getCopyOfContextMap / Context.current().wrap). Post-handler async outbound calls are
    // out of the framework's context scope.
    private static void bindTraceMdc(HttpHeaders requestHeaders) {
        var traceparent = requestHeaders.get(TraceMeta.MDC_TRACEPARENT);
        var ids = TraceMeta.parse(traceparent);
        if (null == ids) {
            // Absent or malformed: forward/log nothing. Do not seed MDC with an invalid context.
            return;
        }
        MDC.put(TraceMeta.MDC_TRACEPARENT, traceparent);
        MDC.put(TraceMeta.MDC_TRACE_ID, ids[0]);
        MDC.put(TraceMeta.MDC_SPAN_ID, ids[1]);
        var tracestate = requestHeaders.get(TraceMeta.TRACESTATE);
        if (null != tracestate) {
            MDC.put(TraceMeta.TRACESTATE, tracestate);
        }
        var requestId = requestHeaders.get(TraceMeta.X_REQUEST_ID);
        if (null != requestId) {
            MDC.put(TraceMeta.X_REQUEST_ID, requestId);
        }
    }

    // OTEL-002 Fix 3: render inbound headers with credential-bearing values masked (LogRedact),
    // keeping keys for debuggability. The access-token JWT cookie and Authorization bearer never
    // appear verbatim in a log line.
    private static String redactHeaders(HttpHeaders headers) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : headers) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            // Header names are case-insensitive; render lower-case for a stable, predictable log
            // (netty preserves the wire case). The value is masked unless allow-listed (LogRedact).
            var key = e.getKey().toLowerCase(java.util.Locale.ROOT);
            sb.append(key).append('=').append(LogRedact.value(key, e.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * A pre-dispatch error response (parse 400 / validation 400 / 404 / parse-time 500). It flows
     * through the same per-connection FIFO queue as handler responses so that, e.g., a bad-body
     * request pipelined behind a slow in-flight request cannot have its 400 overtake the earlier
     * response. Written synchronously on the eventLoop; onDone then frees the FIFO slot.
     */
    private static RequestTask errorTask(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        return onDone -> {
            try {
                writeError(ctx, status, msg);
            } finally {
                onDone.run();
            }
        };
    }

    private static ConnState state(ChannelHandlerContext ctx) {
        var attr = ctx.channel().attr(STATE);
        ConnState st = attr.get();
        if (null == st) {
            st = new ConnState();
            attr.set(st);
        }
        return st;
    }

    /** Appends a request to this connection's FIFO queue and tries to dispatch it. Eventloop-only. */
    private static void enqueue(ChannelHandlerContext ctx, RequestTask task) {
        ConnState st = state(ctx);
        st.queue.addLast(task);
        drain(ctx, st);
    }

    /**
     * Dispatch the next queued request iff none is in flight — strict one-in / one-out so responses
     * on this connection are returned in request order (HTTP/1.1). Runs only on the channel
     * eventLoop, so ConnState needs no synchronization. autoRead is dropped while a request is being
     * served (backpressure: bounds in-flight work to <=1 and stops the socket pulling further
     * pipelined bytes) and re-armed once the queue drains.
     */
    private static void drain(ChannelHandlerContext ctx, ConnState st) {
        if (st.closed) {
            // HARDEN-B4 fix round 2: channel already inactive — drop any queued work and never
            // dispatch on a closed channel (see channelInactive). The queue must be released.
            st.queue.clear();
            return;
        }
        if (st.active) {
            return;
        }
        RequestTask next = st.queue.pollFirst();
        if (null == next) {
            ctx.channel().config().setAutoRead(true);
            return;
        }
        st.active = true;
        ctx.channel().config().setAutoRead(false);
        next.run(() -> {
            // Response for the current request has been written on the eventLoop. Free the slot and
            // dispatch the next queued request. Scheduled (not inline) so a burst of synchronous
            // error responses cannot recurse without bound.
            st.active = false;
            ctx.channel().eventLoop().execute(() -> drain(ctx, st));
        });
    }

    // ADR-0004 (AGENT-001 P1): pull the status-override sentinel out of the response
    // headers (so it is not written to the wire) and map it to the HTTP status; default 200.
    private static HttpResponseStatus extractStatusOverride(List<AsciiHeader> extHeaders) {
        for (int i = 0; i < extHeaders.size(); i++) {
            if (extHeaders.get(i).name.contentEqualsIgnoreCase(STATUS_OVERRIDE_HEADER)) {
                var code = Integer.parseInt(extHeaders.remove(i).value);
                return HttpResponseStatus.valueOf(code);
            }
        }
        return HttpResponseStatus.OK;
    }
    //
    //void writeRpcResult(ChannelHandlerContext ctx, FullHttpRequest request,
    //                    BiFunction<HttpRequest,List<AsciiHeader>,RpcResult> fn){
    //    List<AsciiHeader> extHeaders = new ArrayList<AsciiHeader>();
    //    try {
    //
    //        var res = fn.apply(request,extHeaders);
    //        var content = JsonUtils.stringify(res);
    //        //var status  = res.isOk() ? HttpResponseStatus.OK
    //        // upstream connect error or disconnect/reset before headers. reset reason: protocol error* Closing connection 0
    //        //        : HttpResponseStatus.valueOf(res.getCode());
    //        //< HTTP/1.1 1004 Unknown Status (1004)
    //        writeResponse(ctx , HttpResponseStatus.OK, TYPE_JSON, content.getBytes(StandardCharsets.UTF_8),extHeaders);
    //
    //    } catch (final Exception ex) {
    //        log.error("handler " +request.uri()+ " error",ex);
    //        writeInternalServerError(ctx);
    //    }
    //
    //}

    <ParamDTO> ParamDTO parsePost(FullHttpRequest request, PostHandler<ParamDTO> post) {

        //if(QueryStringDecoder.class == post.getParamClass()){
        //    return (ParamDTO)q;
        //}

        String jsonBody = request.content().toString(CharsetUtil.UTF_8);
        if (null == jsonBody || jsonBody.isBlank()) {
            // AUD-omp-21: a validating endpoint requires a body. Returning null let the handler
            // dereference null -> 500; reject up-front as 400 instead. String and no-validator
            // handlers keep accepting an empty body (null flows to the handler as before).
            if (post.useValidator()) {
                throw new HttpError(HttpResponseStatus.BAD_REQUEST, "request body is required");
            }
            return null;
        }
        if (String.class == post.getParamClass()) {
            return (ParamDTO) jsonBody;
        }
        log.debug("Parse Post Json : {}", jsonBody);
        var input = JsonUtils.parse(jsonBody, post.getParamClass());
        if (post.useValidator()) {
            var violationSet = getValidator().validate(input);
            if (violationSet.size() > 0) {
                // C7 + AUD-omp-21: 400 INVALID_ARGUMENT. The client-facing message carries only
                // field path + constraint message (never getInvalidValue(), which can be PII); the
                // full detail incl. the rejected value goes to the server log only.
                var safe = violationSet.stream()
                        .map(it -> it.getPropertyPath() + " " + it.getMessage())
                        .collect(Collectors.joining("; "));
                var detail = violationSet.stream()
                        .map(it -> it.getPropertyPath() + "=" + it.getInvalidValue() + "(" + it.getMessage() + ")")
                        .collect(Collectors.joining(";"));
                log.warn("validation failed on {}: {}", post.path(), detail);
                throw new HttpError(HttpResponseStatus.BAD_REQUEST,
                        input.getClass().getSimpleName() + " invalid: " + safe);
            }
        }
        return input;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        log.error("exceptionCaught : close ChannelHandlerContext ctx ", cause);
        ctx.close();
    }

    // HARDEN-B4 fix round 2: without this a client that disconnects while request A is in flight and
    // B/C are queued leaks the queued tasks — drain() frees a slot only when A's response is written,
    // so if A hangs the queued work stays pinned forever, and if A completes the next task would be
    // dispatched on an already-dead channel. On channel close mark the ConnState closed, drop the
    // queued tasks, and clear active so the state releases cleanly; drain()/writeResponse then refuse
    // to dispatch or write on the closed channel. Runs on the eventLoop, like all ConnState access.
    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        ConnState st = ctx.channel().attr(STATE).get();
        if (null != st) {
            st.closed = true;
            st.queue.clear();
            st.active = false;
        }
        super.channelInactive(ctx);
    }

    // AUD-omp-52: the pipeline's IdleStateHandler fires this when a connection has been read-idle
    // past HttpServer.READ_IDLE_SECONDS. Close it so a stalled/slow-loris client stops pinning a
    // worker. Non-idle user events are passed through unchanged.
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            log.debug("closing read-idle connection {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * Builds a uniform JSON error envelope {@code {"code":<status>,"message":<msg>}} (C7). A null
     * message falls back to the status reason phrase. AUD-omp-21: callers pass only client-safe
     * text — internal reasons stay in the log — so nothing sensitive reaches the wire.
     */
    private static byte[] errorBody(final HttpResponseStatus status, String msg) {
        if (null == msg) {
            msg = status.reasonPhrase();
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("code", status.code());
        envelope.put("message", msg);
        return JsonUtils.stringify(envelope).getBytes(StandardCharsets.UTF_8);
    }

    /** Writes {@link #errorBody} with {@code content-type: application/json} (body/type always match). */
    private static void writeError(final ChannelHandlerContext ctx, final HttpResponseStatus status, String msg) {
        writeResponse(ctx, status, TYPE_JSON, errorBody(status, msg), null);
    }

    /**
     * Writes a HTTP response.
     *
     * @param ctx The channel context.
     * @param status The HTTP status code.
     * @param contentType The response content type.
     */

    private static void writeResponse(
            final ChannelHandlerContext ctx,
            final HttpResponseStatus status,
            final String contentType,
            byte[] bytes, List<AsciiHeader> extHeaders) {
        // HARDEN-B4 fix round 2: the connection may have closed after this response was scheduled (a
        // handler runs on a virtual thread, so the channel can die while its write is in flight).
        // Never write to a closed channel. This single write chokepoint guards both the handler and
        // the error response paths. See channelInactive.
        if (!ctx.channel().isActive()) {
            return;
        }

        //final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (null == bytes) {
            bytes = Handler.EMPTY;
        }

        final ByteBuf entity = Unpooled.wrappedBuffer(bytes);
        int contentLength = bytes.length;

        // Build the response object.
        final FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                entity,
                false);

        final ZonedDateTime dateTime = ZonedDateTime.now();
        final DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME;

        final DefaultHttpHeaders headers = (DefaultHttpHeaders) response.headers();
        headers.set(HttpHeaderNames.SERVER, SERVER_NAME);
        headers.set(HttpHeaderNames.DATE, dateTime.format(formatter));
        if (null != contentType) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }
        headers.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(contentLength));

        if (null != extHeaders && extHeaders.size() > 0) {
            extHeaders.forEach(h -> headers.add(h.name, h.value));
        }

        ctx.writeAndFlush(response, ctx.voidPromise());
    }

    private static void send100Continue(final ChannelHandlerContext ctx) {
        ctx.write(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.CONTINUE));
    }

    /**
     * A single queued unit of work on one connection. {@code run} MUST call {@code onDone} exactly
     * once — possibly asynchronously, after the response has been written on the eventLoop — so the
     * next queued request is dispatched only then. See {@link #drain}.
     */
    @FunctionalInterface
    private interface RequestTask {
        void run(Runnable onDone);
    }

    /**
     * O6 (HARDEN-B4 fix round 1): per-connection response-ordering state. HTTP/1.1 requires that
     * responses on one connection be returned in request order. Because handle() runs off the
     * eventLoop on a virtual thread, two requests pipelined in a SINGLE TCP segment (both already
     * decoded by the aggregator before setAutoRead(false) can stop the read) would otherwise be
     * dispatched concurrently and race: a fast request could overtake a slow one, so a response
     * could even be framed against the wrong request (data crossing). This FIFO queue guarantees
     * strict one-in / one-out. Touched only on the channel eventLoop — no synchronization needed.
     */
    private static final class ConnState {
        final Deque<RequestTask> queue = new ArrayDeque<>();
        boolean active;
        // HARDEN-B4 fix round 2: set once the channel goes inactive. Guards drain()/writeResponse so
        // queued work is dropped (never dispatched) and nothing is written to a closed channel.
        boolean closed;
    }

    /**
     * Internal signal for a client-side (4xx) failure raised during request parsing/validation.
     * Carries the HTTP status + a client-safe message; caught in channelRead0 and mapped to a JSON
     * error envelope, so it never reaches netty's exceptionCaught / a bare connection reset.
     */
    private static final class HttpError extends RuntimeException {
        final HttpResponseStatus status;
        HttpError(HttpResponseStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

}