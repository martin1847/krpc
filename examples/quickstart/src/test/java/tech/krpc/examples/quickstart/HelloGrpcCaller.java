package tech.krpc.examples.quickstart;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import tech.krpc.client.GeneralizeClient;

/**
 * OTEL-001 B1d (test scope only): makes a real krpc gRPC call to the quickstart server over the
 * wire (loopback :50051). Lives in a CDI bean so all {@code io.grpc} usage runs in the Quarkus
 * application classloader — the same loader as the server — avoiding the @QuarkusTest split-loader
 * {@code LinkageError} that a gRPC client referenced directly from the test class triggers.
 */
@ApplicationScoped
public class HelloGrpcCaller {

    /** Calls {@code quickstart/Hello/hello} and returns the RpcResult code (0 = ok). */
    public int callHello(String name) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", 50051)
                .usePlaintext().build();
        try {
            var out = GeneralizeClient.call(channel, "quickstart/Hello/hello",
                    "{\"name\":\"" + name + "\"}");
            return out.getC();
        } finally {
            channel.shutdownNow();
        }
    }
}
