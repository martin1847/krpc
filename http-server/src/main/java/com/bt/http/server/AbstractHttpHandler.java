/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.http.server;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Validator;

import com.bt.rpc.util.JsonUtils;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Martin.C
 * @version 2021/11/12 5:12 PM
 */
//@ApplicationScoped
@Sharable
public abstract class AbstractHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {


    static final Logger log = LoggerFactory.getLogger(AbstractHttpHandler.class);

    public static final String TYPE_PLAIN = "text/plain; charset=UTF-8";
    public static final String TYPE_JSON = "application/json; charset=UTF-8";
    public static final String SERVER_NAME = "Netty";

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



        if("GET".equals(method)){
            var query = new QueryStringDecoder(uri);
            var handler = getHanlderMap.get(query.rawPath());
            if(null != handler){
                writeHandler(ctx,handler,query,request.headers());
                return;
            }
        }else if("POST".equals(method)){
             var post = postMap.get(uri);
             if(null != post) {
                 var dto = parsePost(request, post);
                 writeHandler(ctx, post, dto,request.headers());
                 return;
             }
        }

        writeNotFound(ctx,uri);

    }
    <ParamDTO> void  writeHandler(ChannelHandlerContext ctx, Handler<ParamDTO> handler, ParamDTO dto, HttpHeaders requestHeaders){
        List<AsciiHeader> extHeaders = new ArrayList<AsciiHeader>();
        try {
            var bytes =  handler.handle(dto,extHeaders,requestHeaders);
            writeResponse(ctx , HttpResponseStatus.OK, handler.contextType(), bytes,extHeaders);
        } catch (final Exception ex) {
            log.error("handler " +handler.path()+ " error",ex);
            writeInternalServerError(ctx,handler.contextType(),ex.getMessage());
        }
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


    <ParamDTO> ParamDTO parsePost(FullHttpRequest request, PostHandler<ParamDTO> post){

        //if(QueryStringDecoder.class == post.getParamClass()){
        //    return (ParamDTO)q;
        //}

        String jsonBody = request.content().toString(CharsetUtil.UTF_8);
        if( null == jsonBody || jsonBody.isBlank()){
            return null;
        }
        if(String.class == post.getParamClass()){
            return (ParamDTO)jsonBody;
        }
        log.debug("Parse Post Json : {}",jsonBody);
        var input = JsonUtils.parse(jsonBody,post.getParamClass());
        if(post.useValidator()){
            var violationSet = getValidator().validate(input);
            if (violationSet.size() > 0) {
                //400: StatusCode.INVALID_ARGUMENT 3;
                throw new RuntimeException(
                        input.getClass().getSimpleName() + " : " + violationSet.stream()
                                .map(it -> it.getPropertyPath() + "=" + it.getInvalidValue() + "(" + it.getMessage() + ")")
                                .collect(Collectors.joining(";"))
                );
            }
        }
        return input;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        log.error("exceptionCaught : close ChannelHandlerContext ctx ",cause);
        ctx.close();
    }

    private static void writeNotFound( ChannelHandlerContext ctx,String uri) {

        var status = HttpResponseStatus.NOT_FOUND;
        writeResponse(ctx, status, TYPE_PLAIN,
                ("{\"code\":404,\"message\":\""+uri+" , "+ status.reasonPhrase()+"\"}").getBytes(StandardCharsets.UTF_8)
                ,null);
    }


    /**
     * Writes a 500 Internal Server Error response.
     *
     * @param ctx The channel context.
     */
    private static void writeInternalServerError(
            final ChannelHandlerContext ctx ,String contextType,String msg) {
        var status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
        if(null == msg){
            msg = status.reasonPhrase();
        }
        if(null == contextType){
            contextType = TYPE_PLAIN;
        }
        writeResponse(ctx, status, contextType, msg.getBytes(StandardCharsets.UTF_8),null);
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
        if(null == bytes){
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

        if(null != extHeaders && extHeaders.size() > 0) {
            extHeaders.forEach(h->headers.add(h.name,h.value));
        }

        ctx.writeAndFlush(response, ctx.voidPromise());
    }

    private static void send100Continue(final ChannelHandlerContext ctx) {
        ctx.write(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.CONTINUE));
    }

}