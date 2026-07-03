/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.http.server;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import io.netty.util.CharsetUtil;
import io.netty.handler.timeout.IdleStateEvent;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.krpc.util.JsonUtils;
import tech.krpc.util.JsonDecodeException;

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
    // default HTTP 200 (e.g. MCP notifications -> 202). It is consumed by writeHandler
    // and never written to the wire.
    public static final String STATUS_OVERRIDE_HEADER = "x-krpc-http-status";

    protected final Map<String, PostHandler> postMap = new HashMap<>();

    protected final Map<String, GetHandler> getHanlderMap = new HashMap<>();

    public abstract Validator getValidator();

    public abstract void initHandler();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {

        if (HttpUtil.is100ContinueExpected(request)) {
            send100Continue(ctx);
        }
        var method = request.method().name();
        var uri = request.uri();

        if ("GET".equals(method)) {
            var query = new QueryStringDecoder(uri);
            var handler = getHanlderMap.get(query.rawPath());
            if (null != handler) {
                writeHandler(ctx, handler, query, request.headers());
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
                    // AUD-omp-20/omp-21: parsePost's JsonUtils.parse ran BEFORE writeHandler's
                    // try, so a malformed body escaped to exceptionCaught -> ctx.close() -> a bare
                    // connection reset with no HTTP response. Now it is a 400 JSON here. Message is
                    // JsonDecodeException's neutral text; Jackson internals stay in the cause/log.
                    log.warn("bad request body on {}: {}", postHandler.path(), ex.getMessage());
                    writeError(ctx, HttpResponseStatus.BAD_REQUEST, ex.getMessage());
                    return;
                } catch (HttpError ex) {
                    // Missing body / validation failure -> 400 (C7 + AUD-omp-21).
                    writeError(ctx, ex.status, ex.getMessage());
                    return;
                } catch (RuntimeException ex) {
                    // Any other parse-time failure: neutral 500, never a silent close.
                    log.error("parse error on {}", postHandler.path(), ex);
                    writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, null);
                    return;
                }

                if (!query.rawQuery().isEmpty() && dto instanceof QueryStringAware) {
                    ((QueryStringAware) dto).setQueryString(query);
                }

                writeHandler(ctx, postHandler, dto, request.headers());
                return;
            }
        }

        writeNotFound(ctx, uri);

    }

    <ParamDTO> void writeHandler(ChannelHandlerContext ctx, Handler<ParamDTO> handler, ParamDTO dto, HttpHeaders requestHeaders) {
        List<AsciiHeader> extHeaders = new ArrayList<AsciiHeader>();
        try {
            var bytes = handler.handle(dto, extHeaders, requestHeaders);
            var status = extractStatusOverride(extHeaders);
            writeResponse(ctx, status, handler.contextType(), bytes, extHeaders);
        } catch (final Exception ex) {
            // C7 + AUD-omp-21: never echo ex.getMessage() to an agent/MCP client — the internal
            // reason stays in the log only; the client gets a neutral 500 JSON envelope.
            log.error("handler " + handler.path() + " error", ex);
            writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, null);
        }
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

    private static void writeNotFound(ChannelHandlerContext ctx, String uri) {
        // C7: 404 as a JSON envelope with a matching content-type (was a PLAIN header wrapping a
        // JSON body). Echoing the not-found URI discloses nothing sensitive.
        writeError(ctx, HttpResponseStatus.NOT_FOUND, uri + " not found");
    }

    /**
     * Writes an error as a uniform JSON envelope {@code {"code":<status>,"message":<msg>}} with
     * {@code content-type: application/json} (C7: body and content-type always match). A null
     * message falls back to the status reason phrase. AUD-omp-21: callers pass only client-safe
     * text — internal reasons stay in the log — so nothing sensitive reaches the wire.
     */
    private static void writeError(final ChannelHandlerContext ctx, final HttpResponseStatus status, String msg) {
        if (null == msg) {
            msg = status.reasonPhrase();
        }
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("code", status.code());
        envelope.put("message", msg);
        var body = JsonUtils.stringify(envelope).getBytes(StandardCharsets.UTF_8);
        writeResponse(ctx, status, TYPE_JSON, body, null);
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