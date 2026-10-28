package tech.krpc.examples.quickstart;

import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;

/**
 * OTEL-003 (test scope only): a second krpc service whose handler makes an OUTBOUND krpc call to
 * {@link HelloService}. This is the piece the OTEL-001/002 container test never exercised — a
 * handler that originates a CLIENT span inside the real Quarkus consumer assembly
 * (quarkus-opentelemetry CDI SDK + BatchSpanProcessor + QuarkusContextStorage). The staging RED was
 * a missing CLIENT span body + a downstream ghost parent; this service reproduces that path.
 */
@RpcService("Chain")
public interface ChainService {

    RpcResult<String> chain(String note);
}
