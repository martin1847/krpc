


```log
-- jvm ok
2023-08-09 22:31:20,612 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-2) [id: 0xf86d617e, L:/127.0.0.1:50051 - R:/127.0.0.1:52068] OUTBOUND HEADERS: streamId=1 headers=GrpcHttp2OutboundHeaders[:status: 200, content-type: application/grpc, grpc-encoding: identity, grpc-accept-encoding: gzip] padding=0 endStream=false
2023-08-09 22:31:20,612 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-2) [id: 0xf86d617e, L:/127.0.0.1:50051 - R:/127.0.0.1:52068] OUTBOUND DATA: streamId=1 padding=0 endStream=false length=75 bytes=00000000461a447b22636f756e74223a372c2264617461223a5b7b226964223a332c226e616d65223a227573657233227d2c7b226964223a342c226e616d6522...
2023-08-09 22:31:20,613 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-2) [id: 0xf86d617e, L:/127.0.0.1:50051 - R:/127.0.0.1:52068] OUTBOUND HEADERS: streamId=1 headers=GrpcHttp2OutboundHeaders[grpc-status: 0] padding=0 endStream=true
2023-08-09 22:31:20,613 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-2) [id: 0xf86d617e, L:/127.0.0.1:50051 - R:/127.0.0.1:52068] INBOUND WINDOW_UPDATE: streamId=1 windowSizeIncrement=75
2023-08-09 22:31:20,613 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-2) [id: 0xf86d617e, L:/127.0.0.1:50051 - R:/127.0.0.1:52068] INBOUND WINDOW_UPDATE: streamId=0 windowSizeIncrement=75
2023-08-09 22:31:20,613 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-2) [id: 0xf86d617e, L:/127.0.0.1:50051 - R:/127.0.0.1:52068] INBOUND GO_AWAY: lastStreamId=0 errorCode=0 length=0 bytes=

```

-- native loss
```log
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] OUTBOUND HEADERS: streamId=1 headers=GrpcHttp2OutboundHeaders[:status: 200, content-type: application/grpc, grpc-encoding: identity, grpc-accept-encoding: gzip] padding=0 endStream=false
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] OUTBOUND DATA: streamId=1 padding=0 endStream=false length=75 bytes=0000000046000000000000000000007b22636f756e74223a372c2264617461223a5b7b226964223a332c226e616d65223a227573657233227d2c7b226964223a...
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] OUTBOUND HEADERS: streamId=1 headers=GrpcHttp2OutboundHeaders[grpc-status: 0] padding=0 endStream=true
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] INBOUND WINDOW_UPDATE: streamId=1 windowSizeIncrement=75
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] INBOUND WINDOW_UPDATE: streamId=0 windowSizeIncrement=75
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] INBOUND RST_STREAM: streamId=1 errorCode=8
2023-08-09 22:34:34,426 DEBUG [io.grp.net.NettyServerHandler] (grpc-nio-worker-ELG-3-1) [id: 0xe10e76bc, L:/127.0.0.1:50051 - R:/127.0.0.1:52120] INBOUND GO_AWAY: lastStreamId=0 errorCode=0 length=0 bytes=
```