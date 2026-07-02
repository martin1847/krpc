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
     * ADR-0004 (AGENT-001 P1): opt this service's methods into the MCP tool surface
     * ({@code POST /mcp} tools/list + tools/call). Default false — agent-tool exposure
     * is a deliberate subset of web exposure, never implied by {@code @UnsafeWeb} alone.
     * The {@code /agent/discover} web view is unaffected by this flag.
     */
    boolean agentTool() default false;

    /**
     * 制定单个方法需要Credential
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface RequireCredential{}

    //@Documented
    //@Retention(RetentionPolicy.RUNTIME)
    //@Target(ElementType.METHOD)
    //@interface SkipCredential{}
}