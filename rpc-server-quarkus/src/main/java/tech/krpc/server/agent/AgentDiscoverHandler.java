package tech.krpc.server.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.krpc.http.server.AbstractHttpHandler;
import tech.krpc.http.server.AsciiHeader;
import tech.krpc.http.server.GetHandler;
import tech.krpc.util.JsonUtils;

/**
 * ADR-0004 (AGENT-001 P0): {@code GET /agent/discover}.
 *
 * <p>Serves the web-only {@link tech.krpc.common.meta.ApiMeta} as JSON: the HTTP
 * analogue of {@code RpcMetaService.listApis()}, but filtered to {@code @UnsafeWeb}
 * services only. Internal/hidden services (including RpcMetaService itself) are never
 * present in this payload.
 */
// AGENT-001 P1 prerequisite: discovered reflectively by HttpHandlerExpose
// (getBeans(Object,Any)); Arc's default remove-unused-beans=all would strip it,
// making /agent/discover absent in a default consumer.
@Unremovable
@ApplicationScoped
public class AgentDiscoverHandler implements GetHandler {

    @Inject
    WebMethodRegistry registry;

    // path() default inference cannot produce a path with a slash; override it.
    @Override
    public String path() {
        return "/agent/discover";
    }

    @Override
    public String contextType() {
        return AbstractHttpHandler.TYPE_JSON;
    }

    @Override
    public byte[] handle(QueryStringDecoder param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders) {
        var meta = registry.apiMeta();
        return JsonUtils.stringify(meta).getBytes(StandardCharsets.UTF_8);
    }
}
