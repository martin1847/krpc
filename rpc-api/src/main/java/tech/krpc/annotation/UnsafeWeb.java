package tech.krpc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口暴露为web-rpc
 * 才可以被前端直接访问，但要注意安全保护
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface UnsafeWeb {

    /**
     * 自动处理header中的 Authorization: Bearer <token.jwt.data>
     */
    boolean requireCredential() default false;

    /**
     * ADR-0004 (AGENT-001 P1): opt <em>every</em> method of this service into the MCP tool
     * surface ({@code POST /mcp} tools/list + tools/call). Default false — agent-tool
     * exposure is a deliberate subset of web exposure, never implied by {@code @UnsafeWeb}
     * alone. For a per-method subset (instead of the whole interface), leave this false and
     * annotate the individual methods with {@link AgentTool}. The {@code /agent/discover}
     * web view is unaffected by this flag.
     */
    boolean agentTool() default false;

    /**
     * 制定单个方法需要Credential
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface RequireCredential{}

    /**
     * AGENT-002: opt a single method of an {@code @UnsafeWeb} interface into the MCP tool
     * surface, without exposing the whole interface. Semantics (NS-6, all-OFF by default):
     * <ul>
     *   <li>{@code @UnsafeWeb(agentTool = true)} on the interface exposes every method
     *       (this annotation is then redundant).</li>
     *   <li>{@code @UnsafeWeb} (agentTool defaults false): only the methods carrying this
     *       annotation are exposed as MCP tools; the rest stay web-only.</li>
     *   <li>No annotation anywhere: no MCP exposure.</li>
     * </ul>
     * Has no effect on a method whose declaring interface is not {@code @UnsafeWeb} (MCP
     * exposure is a strict subset of web exposure).
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface AgentTool{}

    //@Documented
    //@Retention(RetentionPolicy.RUNTIME)
    //@Target(ElementType.METHOD)
    //@interface SkipCredential{}
}