package tech.krpc.ext.gen.smokefixture;

import tech.krpc.annotation.RpcService;
import tech.krpc.annotation.UnsafeWeb;
import tech.krpc.model.RpcResult;

/**
 * GEN-NETTY-102: a minimal {@code @RpcService @UnsafeWeb} interface that {@link
 * tech.krpc.ext.gen.Gen#scan} will pick up. @UnsafeWeb is required for scan to enter the
 * {@code RpcServerBuilder.toMeta} branch — the exact call that first links
 * {@code tech.krpc.server.RpcServerBuilder} (→ {@code io.grpc.netty.NettyServerBuilder}).
 */
@RpcService(description = "gen-netty-102 smoke")
@UnsafeWeb
public interface ISmokeService {
    RpcResult<SmokeDto> echo(SmokeDto req);
}
