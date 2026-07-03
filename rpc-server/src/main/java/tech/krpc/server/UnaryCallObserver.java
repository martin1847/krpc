package tech.krpc.server;

import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;

import static com.google.common.base.Preconditions.checkState;

public class UnaryCallObserver
      extends ServerCallStreamObserver<OutputProto> {
    final         ServerCall<InputProto, OutputProto> call;
    volatile boolean                                  cancelled;
    private boolean                                   frozen;
    private boolean                                   autoRequestEnabled = true;
    private boolean                                   sentHeaders;
    Runnable onReadyHandler;
    Runnable onCancelHandler;
    // O5 (fix-round-1): a SINGLE terminal gate for every path that ends the call — onNext's send
    // (a failed send means the call is already dead transport-side), onError, and onCompleted all
    // check-and-set this before call.close(). The first terminal outcome wins; every later close()
    // is a no-op, so a second terminal signal can never throw "call already closed" and mask the
    // first real status. Replaces the split aborted/completed flags, which onNext never set — so a
    // send that threw left both false and the outer unary catch's onError re-closed (the O5 leak).
    private boolean terminal = false;

    private   Metadata headers;

    // Non private to avoid synthetic class
    UnaryCallObserver(ServerCall<InputProto, OutputProto> call, Metadata headers) {
      this.call = call;
      this.headers = headers;
    }

    Metadata getHeaders(){
        return headers;
    }

    void freeze() {
      this.frozen = true;
    }

    @Override
    public void setMessageCompression(boolean enable) {
      call.setMessageCompression(enable);
    }

    @Override
    public void setCompression(String compression) {
      call.setCompression(compression);
    }

    @Override
    public void onNext(OutputProto response) {
        // O5 (fix-round-1): a terminal outcome already decided ⇒ never send again (idempotent).
        if (terminal) {
            return;
        }
        // AUD-omp-12 (#3): client already cancelled ⇒ the call is closed transport-side; sending
        // would race that close. Short-circuit. call.isCancelled() is authoritative; `cancelled`
        // mirrors onCancel (was write-only — now read here).
        if (call.isCancelled() || cancelled) {
            return;
        }
        // O5 (fix-round-1): a send that throws means the call is already dead (transport closed it).
        // Latch terminal BEFORE the exception escapes so the outer unary catch's onError() sees the
        // gate set and does NOT attempt a second close() — pre-fix that second close threw "call
        // already closed" and masked the real send failure. Then rethrow the true first error.
        try {
            if (!sentHeaders) {
                call.sendHeaders(ServerContext.current().getResponseHeaders());
                sentHeaders = true;
            }
            call.sendMessage(response);
        } catch (RuntimeException e) {
            terminal = true;
            throw e;
        }
    }

    @Override
    public void onError(Throwable t) {
      // O5 (fix-round-1): route through the single terminal gate. The FIRST terminal signal wins; a
      // later onError/onCompleted (or an onNext send that already latched terminal) is a no-op.
      Metadata metadata = Status.trailersFromThrowable(t);
      if (metadata == null) {
        metadata = new Metadata();
      }
      closeOnce(Status.fromThrowable(t), metadata);
    }

    @Override
    public void onCompleted() {
      // O5 (fix-round-1): route through the single terminal gate — see onError.
      closeOnce(Status.OK, new Metadata());
    }

    // O5 (fix-round-1): THE single terminal gate. Every path that ends the call closes through here.
    // Latch `terminal` BEFORE call.close() so even a throwing close() leaves the gate set — a later
    // terminal call then no-ops instead of re-closing (which throws "call already closed" and masks
    // the real status). AUD-omp-12: a cancelled call is already closed transport-side, so skip close.
    private void closeOnce(Status status, Metadata trailers) {
      if (terminal) {
        return;
      }
      terminal = true;
      if (call.isCancelled() || cancelled) {
        return;
      }
      call.close(status, trailers);
    }

    @Override
    public boolean isReady() {
      return call.isReady();
    }

    @Override
    public void setOnReadyHandler(Runnable r) {
      checkState(!frozen, "Cannot alter onReadyHandler after initialization. May only be called "
          + "during the initial call to the application, before the service returns its "
          + "StreamObserver");
      this.onReadyHandler = r;
    }

    @Override
    public boolean isCancelled() {
      return call.isCancelled();
    }

    @Override
    public void setOnCancelHandler(Runnable onCancelHandler) {
      checkState(!frozen, "Cannot alter onCancelHandler after initialization. May only be called "
          + "during the initial call to the application, before the service returns its "
          + "StreamObserver");
      this.onCancelHandler = onCancelHandler;
    }

    @Deprecated
    @Override
    public void disableAutoInboundFlowControl() {
      disableAutoRequest();
    }

    @Override
    public void disableAutoRequest() {
      checkState(!frozen, "Cannot disable auto flow control after initialization");
      autoRequestEnabled = false;
    }

    @Override
    public void request(int count) {
      call.request(count);
    }
  }
