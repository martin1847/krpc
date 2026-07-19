package tech.krpc.examples.quickstart;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.enterprise.context.ApplicationScoped;

import tech.krpc.client.RpcClientFactory;
import tech.krpc.model.RpcResult;

/**
 * OTEL-003 (test scope only): drives {@code quickstart/Chain/chain} through a real typed krpc
 * client (OTel-instrumented) over the wire, so the full chain
 * CLIENT/chain -> SERVER/chain -> CLIENT/hello -> SERVER/hello is exercised inside the Quarkus
 * consumer assembly. All {@code io.grpc} usage lives in this CDI bean (application classloader) to
 * avoid the &#64;QuarkusTest split-loader LinkageError.
 */
@ApplicationScoped
public class ChainGrpcCaller {

    public String callChain(String note) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", 50051)
                .usePlaintext().build();
        try {
            ChainService client = new RpcClientFactory("quickstart", channel).get(ChainService.class);
            RpcResult<String> r = client.chain(note);
            return r.isOk() ? r.getData() : ("ERR:" + r.getCode());
        } finally {
            channel.shutdownNow();
        }
    }
}
