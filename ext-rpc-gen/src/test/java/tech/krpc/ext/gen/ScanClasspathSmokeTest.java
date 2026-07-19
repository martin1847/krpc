package tech.krpc.ext.gen;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tech.krpc.ext.gen.meta.ApiMetaRoot;

/**
 * GEN-NETTY-102 regression guard.
 *
 * <p>{@link Gen#scan} calls {@code RpcServerBuilder.toMeta}/{@code buildApiMeta}, which links
 * {@code tech.krpc.server.RpcServerBuilder} — a class that references
 * {@code io.grpc.netty.NettyServerBuilder}. rpc-server declares grpc-netty {@code compileOnly}
 * by design, so ext-rpc-gen (a leaf that reaches this scan path) MUST carry grpc-netty as its
 * own runtime dependency. On 1.0.1 it did not, and a clean consumer classpath threw
 * {@code NoClassDefFoundError} at scan time. This test exercises the scan path so any future
 * loss of a scan-reachable runtime dependency fails the module test instead of a consumer.
 *
 * <p><b>Coverage boundary:</b> this runs on the in-repo <em>test</em> classpath, where
 * rpc-server + grpc-netty are always present. It proves the scan path links and produces meta;
 * it does NOT reproduce the published-POM dependency view a standalone consumer resolves. The
 * consumer-POM guarantee is verified separately by grepping the generated publication POM for
 * {@code grpc-netty} (scope runtime, version 1.79.0) — see GEN-NETTY-102 findings.
 */
class ScanClasspathSmokeTest {

    @Test
    void scanLinksRpcServerBuilderAndBuildsMeta() throws Exception {
        ApiMetaRoot root = Gen.scan("gen-netty-102", "tech.krpc.ext.gen.smokefixture");

        assertNotNull(root, "scan must return a meta root");
        assertNotNull(root.getApis(), "scan must populate apis");
        assertTrue(
                root.getApis().stream()
                        .flatMap(a -> a.getMethods().stream())
                        .anyMatch(m -> "echo".equals(m.getName())),
                "scan must discover the @UnsafeWeb ISmokeService.echo endpoint");
    }
}
