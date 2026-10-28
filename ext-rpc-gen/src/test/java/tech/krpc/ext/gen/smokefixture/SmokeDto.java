package tech.krpc.ext.gen.smokefixture;

/**
 * GEN-NETTY-102: minimal DTO for the clean-classpath scan smoke test. Plain non-static fields
 * so {@code RpcMetaServiceImpl.cls2dto} produces meta without needing Lombok/getters.
 */
public class SmokeDto {
    public String name;
    public int count;
}
