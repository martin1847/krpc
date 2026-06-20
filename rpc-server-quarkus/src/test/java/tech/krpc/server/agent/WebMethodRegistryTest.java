package tech.krpc.server.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.krpc.server.RpcServerBuilder;
import tech.krpc.server.WebInvoker;

/**
 * AGENT-001 P0: the web surface built by {@link RpcServerBuilder} must contain web
 * (@UnsafeWeb) services only. Hidden services (incl. the always-registered internal
 * RpcMetaService) must be absent from both the dispatch map and the discovery ApiMeta.
 */
class WebMethodRegistryTest {

    private RpcServerBuilder build() throws Exception {
        // High port; build() does not bind (startServer() would).
        var builder = new RpcServerBuilder.Builder("testapp", 59123);
        builder.addService(new AgentTestServices.WebEcho());
        builder.addService(new AgentTestServices.HiddenAdmin());
        return builder.build();
    }

    @Test
    void webMethods_containWebService_excludeHiddenAndInternal() throws Exception {
        var server = build();
        var web = server.webMethods();

        assertNotNull(web.get("WebEcho/echo"), "web service method must be registered");

        // Hidden service (no @UnsafeWeb) must never enter the web surface, under any key.
        assertTrue(web.keySet().stream().noneMatch(k -> k.contains("HiddenAdmin")),
                "hidden service must not be in web dispatch map: " + web.keySet());

        // The internal RpcMetaService is hidden and must not be exposed either.
        assertTrue(web.keySet().stream().noneMatch(k -> k.contains("RpcMeta") || k.contains("MService")),
                "internal meta services must not be in web dispatch map: " + web.keySet());
    }

    @Test
    void webApiMeta_excludeHiddenServices() throws Exception {
        var server = build();
        var meta = server.webApiMeta();

        assertNotNull(meta);
        var names = meta.getApis().stream().map(a -> a.getName()).toList();

        assertTrue(names.contains("WebEcho"), "web service must be discoverable: " + names);
        assertFalse(names.stream().anyMatch(n -> n.contains("HiddenAdmin")),
                "hidden service must not be discoverable: " + names);
        // every discovered api must be web==true
        assertTrue(meta.getApis().stream().allMatch(a -> Boolean.TRUE.equals(a.getWeb())),
                "discovery must only contain web==true services");
    }

    @Test
    void registry_lookup_unknownReturnsNull() {
        var registry = new WebMethodRegistry();
        WebInvoker invoker = (in, headers) -> null;
        registry.init(java.util.Map.of("WebEcho/echo", invoker), null);

        assertNotNull(registry.lookup("WebEcho", "echo"));
        assertNull(registry.lookup("WebEcho", "ghost"), "unknown method must resolve to null");
        assertNull(registry.lookup("HiddenAdmin", "secret"), "hidden service must resolve to null");
        assertNull(registry.lookup(null, "echo"));
    }
}
