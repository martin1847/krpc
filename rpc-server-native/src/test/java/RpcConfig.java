import java.util.Optional;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "rpc.server")
public interface RpcConfig {
    /**
     */
    //Optional<String> globalFilter();


    //@ConfigProperty
    Optional<Integer> port();

    /**
     * app name
     */
    Optional<String> app() ;


    Optional<String> jwks() ;

    Optional<String> jwtCookie() ;

    Optional<Boolean> exitOnJwksError() ;
}
