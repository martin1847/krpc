package tech.krpc.server.agent;

/**
 * ADR-0004 (AGENT-001 P0): request body for {@code POST /agent/invoke}.
 *
 * <p>{@code input} is captured as a free-form value (Jackson maps arbitrary JSON to
 * Map/List/scalar) and re-serialized verbatim into the generic invoke path
 * ({@link tech.krpc.internal.InputProto#setUtf8}), exactly like {@code GeneralizeClient}'s
 * JSON mode. It is intentionally not parsed into a typed DTO here; the target method does
 * its own deserialization + validation inside the dispatch path.
 */
public class AgentInvokeRequest {

    private String service;
    private String method;
    private Object input;

    public AgentInvokeRequest() {
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }
}
