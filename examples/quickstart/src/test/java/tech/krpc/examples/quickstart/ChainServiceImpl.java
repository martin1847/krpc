package tech.krpc.examples.quickstart;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.runtime.Startup;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;

import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;

/**
 * OTEL-003 (test scope only): a krpc service whose handler makes a real outbound krpc call to
 * {@link HelloService} over the wire (loopback :50051, a self-hop within the same Quarkus process).
 * The outbound call goes through the OTel-instrumented {@code MethodCallProxyHandler} /
 * {@code OtelClientInterceptor}, opening a CLIENT span that should be a child of this service's
 * SERVER span and the parent of the downstream {@code /hello} SERVER span.
 *
 * <p>Three outbound modes select the consumer execution topology (by {@code note} prefix), which is
 * what determines whether the trace stays connected under the real {@code QuarkusContextStorage}:
 * <ul>
 *   <li><b>plain</b> (default) — synchronous on the krpc handler virtual thread. krpc's SERVER span
 *       lives in QuarkusContextStorage's ThreadLocal fallback (krpc runs on its own non-Vert.x
 *       thread) and the outbound reads it back on the same thread: chain stays connected.</li>
 *   <li><b>{@code managed}</b> — via a MicroProfile {@link ManagedExecutor} (Quarkus's
 *       context-propagating executor). Propagation captures the OTel context from the ThreadLocal
 *       fallback and restores it on the worker: chain stays connected.</li>
 *   <li><b>{@code vertx}</b> — on a fresh Vert.x duplicated context via {@code executeBlocking}
 *       WITHOUT context capture. Neither the OTel context nor MDC crosses the hop, so the outbound
 *       CLIENT span becomes an orphan root in a new trace. This is a consumer-side propagation break
 *       (OTEL-002 R1-8), reproduced here to pin the boundary — NOT a krpc defect.</li>
 * </ul>
 *
 * <p>All {@code io.grpc} usage lives in this CDI bean (application classloader) to avoid the
 * &#64;QuarkusTest split-loader LinkageError, mirroring {@link HelloGrpcCaller}.
 */
@ApplicationScoped
@Startup
public class ChainServiceImpl implements ChainService {

    @Inject
    Vertx vertx;

    @Inject
    ManagedExecutor managedExecutor;

    private volatile ManagedChannel channel;
    private volatile HelloService helloClient;

    private HelloService client() {
        HelloService c = helloClient;
        if (c == null) {
            synchronized (this) {
                if (helloClient == null) {
                    channel = ManagedChannelBuilder.forAddress("127.0.0.1", 50051)
                            .usePlaintext().build();
                    helloClient = new RpcClientFactory("quickstart", channel).get(HelloService.class);
                }
                c = helloClient;
            }
        }
        return c;
    }

    @Override
    public RpcResult<String> chain(String note) {
        HelloRequest req = new HelloRequest();
        req.setName(note);
        RpcResult<HelloReply> down;
        if (note != null && note.startsWith("managed")) {
            down = helloOnManagedExecutor(req);
        } else if (note != null && note.startsWith("vertx")) {
            down = helloOnVertxContext(req);
        } else {
            down = client().hello(req);
        }
        if (!down.isOk()) {
            return RpcResult.error(down.getCode(), "downstream hello failed");
        }
        return RpcResult.ok("chained:" + down.getData().getMessage());
    }

    /** Context-propagating executor: captures + restores the OTel context across the hop. */
    private RpcResult<HelloReply> helloOnManagedExecutor(HelloRequest req) {
        try {
            return managedExecutor.supplyAsync(() -> client().hello(req)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Fresh Vert.x duplicated context WITHOUT context capture: the caller's context is lost. */
    private RpcResult<HelloReply> helloOnVertxContext(HelloRequest req) {
        io.vertx.core.Context dup = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CompletableFuture<RpcResult<HelloReply>> cf = new CompletableFuture<>();
        dup.executeBlocking(() -> client().hello(req))
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        cf.complete(ar.result());
                    } else {
                        cf.completeExceptionally(ar.cause());
                    }
                });
        try {
            return cf.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PreDestroy
    void close() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
