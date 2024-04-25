/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.krpc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import tech.krpc.server.jws.Es256Jws;
import tech.krpc.server.jws.JwsCredential;
import tech.krpc.server.jws.Murmur3;
import tech.krpc.server.jws.PublicClaims;

/**
 *
 * @author martin.cong
 * @version 2022-04-12 17:58
 */
public interface TestBase {

    String kid();

    String priB64();


    //[{"sub": "1001", "adm": 1}]
    List<UserJwt> users();

    default String cid(){
        return "python-yaml-test";
    }

    default boolean bindClient(){
        return true;
    }

    default long expSecond(){
        return 180*24*3600;
    }

    default void genJwt(){

        var expAt = System.currentTimeMillis() / 1000 + expSecond();
        var hash = Murmur3.hash64(cid().getBytes(StandardCharsets.UTF_8));

        var jws = new Es256Jws(kid(),priB64());
        for(var u : users()) {
            var claims = u.getClaims();
            claims.put(PublicClaims.EXPIRES_AT,expAt);
            if (bindClient()) {
                claims.put(JwsCredential.CLIENT_HASH_LONG, hash);
            }
            var token = jws.jws(claims);
            System.out.println("| " + u.getUserName() +" | " + token +" | "+  " |");
        }
    }


}