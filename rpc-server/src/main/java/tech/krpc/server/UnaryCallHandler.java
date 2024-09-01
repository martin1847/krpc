/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server;

import tech.krpc.internal.InputProto;
import tech.krpc.internal.OutputProto;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

/**
 *
 * @author Martin.C
 * @version 2021/10/11 1:18 PM
 */
public class UnaryCallHandler implements ServerCallHandler<InputProto, OutputProto> {

    private final UnaryMethod method;

    // Non private to avoid synthetic class
    public UnaryCallHandler(UnaryMethod method) {
        this.method = method;
    }

    @Override
    public ServerCall.Listener<InputProto> startCall(ServerCall<InputProto, OutputProto> call
            , Metadata headers) {
        // We expect only 1 request, but we ask for 2 requests here so that if a misbehaving client
        // sends more than 1 requests, ServerCall will catch it. Note that disabling auto
        // inbound flow control has no effect on unary calls.
        call.request(2);
        return new UnaryServerCallListener( call , headers);
    }
    final class UnaryServerCallListener extends ServerCall.Listener<InputProto> {
        private final ServerCall<InputProto, OutputProto> call;
        private final UnaryCallObserver                   responseObserver;
        private       boolean                               canInvoke = true;
        private       boolean                               wasReady;
        private       InputProto                            request;

        // Non private to avoid synthetic class
        UnaryServerCallListener(ServerCall<InputProto, OutputProto> call, Metadata headers) {
            this.call = call;
            this.responseObserver = new UnaryCallObserver(call,headers);
        }

        @Override
        public void onMessage(InputProto request) {
            if (this.request != null) {
                // Safe to close the call, because the application has not yet been invoked
                // ServerCalls.TOO_MANY_REQUESTS
                call.close(
                        Status.INTERNAL.withDescription("Too many requests"),
                        new Metadata());
                canInvoke = false;
                return;
            }

            // We delay calling method.invoke() until onHalfClose() to make sure the client
            // half-closes.
            this.request = request;
        }

        @Override
        public void onHalfClose() {
            if (!canInvoke) {
                return;
            }
            if (request == null) {
                // Safe to close the call, because the application has not yet been invoked
                // ServerCalls.TOO_MANY_REQUESTS
                call.close(
                        Status.INTERNAL.withDescription("Half-closed without a request"),
                        new Metadata());
                return;
            }

            method.invoke(request, responseObserver);
            request = null;
            responseObserver.freeze();
            if (wasReady) {
                // Since we are calling invoke in halfClose we have missed the onReady
                // event from the transport so recover it here.
                onReady();
            }
        }

        @Override
        public void onCancel() {
            if (responseObserver.onCancelHandler != null) {
                responseObserver.onCancelHandler.run();
            } else {
                // Only trigger exceptions if unable to provide notification via a callback
                responseObserver.cancelled = true;
            }
        }

        @Override
        public void onReady() {
            wasReady = true;
            if (responseObserver.onReadyHandler != null) {
                responseObserver.onReadyHandler.run();
            }
        }
    }
}