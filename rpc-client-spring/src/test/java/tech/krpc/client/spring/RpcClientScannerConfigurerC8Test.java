package tech.krpc.client.spring;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import tech.krpc.client.RpcClientFactory;
import tech.krpc.client.spring.testsvc.DemoRpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDEN-B2 / C8 regression tests for {@link RpcClientScannerConfigurer}.
 *
 * <p>Two fixes are defended here, each with a mutation-proof assertion:
 * <ul>
 *   <li><b>Channel-leak fix.</b> The client factory is now registered as a bean
 *       {@code "rpcClientFactory-<appName>"} carrying {@code destroyMethodName="close"}, so context
 *       shutdown drains its {@link ManagedChannel}. Pre-fix the factory was a local variable
 *       captured only by the client bean-supplier lambda and never registered, so no factory bean
 *       existed and its channel + gRPC executor threads leaked on every refresh.</li>
 *   <li><b>Default-port fallback.</b> {@code URL.getPort()} is {@code -1} for a portless config URL;
 *       that {@code -1} used to reach {@code ManagedChannelBuilder.forAddress(host, -1)}, producing a
 *       bare authority like {@code "localhost"}. The fix falls back to {@code url.getDefaultPort()}
 *       (http&rarr;80, https&rarr;443).</li>
 * </ul>
 *
 * <p>Everything runs in-process: {@code ManagedChannelBuilder...build()} is lazy, so the channel's
 * {@code authority()} and {@code isShutdown()} are observable with no live server and no network.
 *
 * <p>Reflecting the private {@code channel} field is a deliberate white-box reach: the wire
 * authority is the externally meaningful contract the default-port fix changes, but
 * {@link RpcClientFactory} exposes no getter for its channel, so reflection is the only in-process
 * observation point.
 */
class RpcClientScannerConfigurerC8Test {

    /** Package containing the single scannable {@link DemoRpc} {@code @RpcService} interface. */
    private static final String SCAN_PKG = "tech.krpc.client.spring.testsvc";

    /** A package with no {@code @RpcService} interface, used to exercise the skip-on-empty path. */
    private static final String EMPTY_PKG = "tech.krpc.client.spring.testsvc.nope";

    /**
     * Every bean factory a test drives, so teardown can drain its channels. The scanner builds the
     * {@link ManagedChannel} eagerly in {@code postProcessBeanDefinitionRegistry} (before any bean
     * is instantiated), so a test that only inspects definitions still leaves a live channel that
     * would trip gRPC's orphan-channel leak detector. We refuse to leak the very resource C8 fixes.
     */
    private final List<DefaultListableBeanFactory> factories = new ArrayList<>();

    @AfterEach
    void drainChannels() {
        for (var bf : factories) {
            // Instantiate any not-yet-created factory singletons, then run their close() destroy
            // method, shutting the eagerly-built channel down deterministically.
            bf.getBeansOfType(RpcClientFactory.class);
            bf.destroySingletons();
        }
        factories.clear();
    }

    private DefaultListableBeanFactory driveScanner(String appName, String url, String scanPkg) {
        var beanFactory = new DefaultListableBeanFactory();
        factories.add(beanFactory);

        var rpcCfg = new RpcCfg();
        rpcCfg.setUrl(url);
        rpcCfg.setScan(scanPkg);

        var cfg = new RpcClientScannerConfigurer();
        cfg.setClients(Map.of(appName, rpcCfg));
        cfg.setCacheManager(null); // null just logs; no cache wiring needed for C8

        cfg.postProcessBeanDefinitionRegistry(beanFactory);
        return beanFactory;
    }

    private static ManagedChannel reflectChannel(RpcClientFactory fac) throws Exception {
        Field f = RpcClientFactory.class.getDeclaredField("channel");
        f.setAccessible(true);
        var channel = (ManagedChannel) f.get(fac);
        assertNotNull(channel, "factory should hold a non-null ManagedChannel");
        return channel;
    }

    /**
     * Leak fix: the factory is registered as a bean whose destroy method is {@code close}.
     * Pre-fix there was no factory bean at all, so {@code containsBeanDefinition} was false and
     * there was no destroy hook to shut the channel down.
     */
    @Test
    void factoryRegisteredAsCloseableBean() {
        var beanFactory = driveScanner("demo", "http://localhost", SCAN_PKG);

        assertTrue(beanFactory.containsBeanDefinition("rpcClientFactory-demo"),
                "C8 leak fix: factory must be registered as a bean (pre-fix: never registered)");

        BeanDefinition bd = beanFactory.getBeanDefinition("rpcClientFactory-demo");
        assertEquals("close", bd.getDestroyMethodName(),
                "C8 leak fix: factory bean must carry destroyMethodName=close so context shutdown drains the channel");

        // Sanity: the scan really did find DemoRpc and register its client bean.
        assertTrue(beanFactory.containsBeanDefinition(DemoRpc.class.getName()),
                "scanner should have registered the DemoRpc client bean");
    }

    /**
     * Default-port fallback for a portless {@code http://} URL: authority resolves to
     * {@code localhost:80}. Pre-fix {@code forAddress(host, -1)} yielded bare {@code "localhost"}.
     */
    @Test
    void httpDefaultPortIs80() throws Exception {
        var beanFactory = driveScanner("demo", "http://localhost", SCAN_PKG);

        var fac = beanFactory.getBean("rpcClientFactory-demo", RpcClientFactory.class);
        var channel = reflectChannel(fac);

        assertEquals("localhost:80", channel.authority(),
                "C8 default-port fix: portless http:// must fall back to port 80 (pre-fix bug: bare \"localhost\")");
    }

    /**
     * Default-port fallback for a portless {@code https://} URL: authority resolves to
     * {@code localhost:443}. TLS builder is lazy, so no certs/network are needed.
     */
    @Test
    void httpsDefaultPortIs443() throws Exception {
        var beanFactory = driveScanner("secure", "https://localhost", SCAN_PKG);

        var fac = beanFactory.getBean("rpcClientFactory-secure", RpcClientFactory.class);
        var channel = reflectChannel(fac);

        assertEquals("localhost:443", channel.authority(),
                "C8 default-port fix: portless https:// must fall back to port 443 (pre-fix bug: bare \"localhost\")");
    }

    /**
     * An explicit port in the config URL must be honoured verbatim (the fallback only kicks in on
     * {@code getPort() < 0}). Guards against a fix that clobbers explicit ports with the default.
     */
    @Test
    void explicitPortIsHonoured() throws Exception {
        var beanFactory = driveScanner("demo", "http://localhost:50051", SCAN_PKG);

        var fac = beanFactory.getBean("rpcClientFactory-demo", RpcClientFactory.class);
        var channel = reflectChannel(fac);

        assertEquals("localhost:50051", channel.authority(),
                "explicit port must be preserved, not overwritten by the default-port fallback");
    }

    /**
     * Close wiring end-to-end: instantiating the factory singleton then closing the bean factory
     * must run the {@code close} destroy method, shutting the channel down. Pre-fix (no factory
     * bean / no destroy method) the channel would never be shut down.
     */
    @Test
    void destroyMethodShutsChannelDown() throws Exception {
        var beanFactory = driveScanner("demo", "http://localhost", SCAN_PKG);

        var fac = beanFactory.getBean("rpcClientFactory-demo", RpcClientFactory.class);
        var channel = reflectChannel(fac);
        assertFalse(channel.isShutdown(), "channel must be live before context shutdown");

        // The supplier returns the same factory instance the channel lives on.
        assertSame(fac, beanFactory.getBean("rpcClientFactory-demo", RpcClientFactory.class),
                "factory bean must be a singleton so destroySingletons closes the live channel");

        beanFactory.destroySingletons();

        assertTrue(channel.isShutdown(),
                "C8: destroyMethod=close must run channel.shutdown() on context close (channel left running pre-fix)");
    }

    /**
     * Skip-on-empty: scanning a package with no {@code @RpcService} interface registers nothing.
     * Guards the {@code clzSet.isEmpty() -> continue} branch; a bug dropping the continue would
     * register a factory bean (and construct a channel) for a config that resolves zero clients.
     */
    @Test
    void noRpcServiceInPackageSkipsRegistration() {
        var beanFactory = driveScanner("empty", "http://localhost", EMPTY_PKG);

        assertFalse(beanFactory.containsBeanDefinition("rpcClientFactory-empty"),
                "no @RpcService in scan package must skip registration entirely");
    }
}
