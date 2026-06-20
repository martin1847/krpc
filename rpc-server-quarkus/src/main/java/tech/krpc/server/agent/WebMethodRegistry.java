package tech.krpc.server.agent;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.common.meta.ApiMeta;
import tech.krpc.server.WebInvoker;

/**
 * ADR-0004 (AGENT-001 P0): the web-only dispatch + discovery surface for HTTP agents.
 *
 * <p>Holds only {@code @UnsafeWeb} services. Hidden services (e.g. RpcMetaService) are
 * never placed here by {@link tech.krpc.server.RpcServerBuilder}, so they are neither
 * discoverable nor invocable over HTTP. This is the second of the two hidden-service
 * rejection points (the first is registration-time in RpcServerBuilder).
 */
@ApplicationScoped
public class WebMethodRegistry {

    /// app-relative "Service/method" -> web UnaryMethod
    private Map<String, WebInvoker> webMethods = Map.of();

    /// ApiMeta containing only web services and their DTO closure
    private ApiMeta webApiMeta;

    public void init(Map<String, WebInvoker> webMethods, ApiMeta webApiMeta) {
        this.webMethods = webMethods;
        this.webApiMeta = webApiMeta;
    }

    /**
     * @return the web UnaryMethod for "service/method", or {@code null} if the method is
     * not a web service (unknown or hidden). Callers must treat {@code null} as 404.
     */
    public WebInvoker lookup(String service, String method) {
        if (null == service || null == method) {
            return null;
        }
        return webMethods.get(service + "/" + method);
    }

    public ApiMeta apiMeta() {
        return webApiMeta;
    }
}
