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
    private boolean aborted = false;
    private boolean completed = false;

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
        // O5 + AUD-omp-08 (#2): self-idempotent — never send after a terminal event.
        checkState(!aborted, "Stream was terminated by error, no further calls are allowed");
        checkState(!completed, "Stream is already completed, no further calls are allowed");
        // AUD-omp-12 (#3): client already cancelled ⇒ the call is closed transport-side; sending
        // would race that close. Short-circuit. call.isCancelled() is authoritative; `cancelled`
        // mirrors onCancel (was write-only — now read here).
        if (call.isCancelled() || cancelled) {
            return;
        }
        if (!sentHeaders) {
            call.sendHeaders(ServerContext.current().getResponseHeaders());
            sentHeaders = true;
        }
        call.sendMessage(response);
    }

    @Override
    public void onError(Throwable t) {
      // O5 + AUD-omp-08 (#2): self-idempotent. The FIRST terminal signal wins; a second
      // onError/onCompleted (e.g. UnaryMethod's catch firing after onCompleted's close() threw) is a
      // no-op — never a second call.close() (which throws "call already closed" and masks the real
      // error). Set the flag BEFORE close() so even a throwing close() leaves us guarded on re-entry.
      if (aborted || completed) {
        return;
      }
      aborted = true;
      // AUD-omp-12 (#3): a cancelled call is already closed; a second close() would throw.
      if (call.isCancelled() || cancelled) {
        return;
      }
      Metadata metadata = Status.trailersFromThrowable(t);
      if (metadata == null) {
        metadata = new Metadata();
      }
      call.close(Status.fromThrowable(t), metadata);
    }

    @Override
    public void onCompleted() {
      // O5 + AUD-omp-08 (#2): self-idempotent — see onError.
      if (aborted || completed) {
        return;
      }
      completed = true;
      // AUD-omp-12 (#3): cancelled call is already closed; skip the close race.
      if (call.isCancelled() || cancelled) {
        return;
      }
      call.close(Status.OK, new Metadata());
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
