/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.jws;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import tech.krpc.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/11/15 5:34 PM
 */
@Slf4j
public class Es256Jws {

    final String kid;
    final String ecPriBase64;
    final boolean bindClient;


    static final Consumer<Map<String,Object>> EMPTY = map->{};

    private String         jwtHeader64;
    private Es256Signature signature;

    public Es256Jws(String kid, String ecPriBase64){
        this(kid,ecPriBase64,false);
    }
    public Es256Jws(String kid, String ecPriBase64 ,boolean bindClient) {
        this.kid = kid;
        this.ecPriBase64 = ecPriBase64;
            //var headerJson = "{\"kid\":\"" + kid + "\",\"alg\":\"ES256\"}";
        var headers = new HashMap<String,String>(8);
        headers.put(PublicClaims.ALGORITHM,Es256Jwk.JWS_ALG);
        headers.put(PublicClaims.KEY_ID,kid);
        jwtHeader64 =  Es256Signature.base64(JsonUtils.stringify(headers).getBytes(StandardCharsets.UTF_8));
        signature = new Es256Signature(ecPriBase64);
        this.bindClient = bindClient;

        //System.out.println("JWT Test : ========== " + jwt("1234", "w-xxxx",
        //        true, Instant.now().getEpochSecond() + 3600));
    }

    //public String jws(String subject, long epochSecond){
    //    return jws(subject,epochSecond,EMPTY);
    //}

    public String jws(String subject, long epochSecond, Consumer<Map<String, Object>> bodyRender) {
        return jws(subject,epochSecond,"",bodyRender);
    }

    public String jws(String subject, long epochSecond, String clientId, Consumer<Map<String, Object>> bodyRender) {

        var payloadClaims = new HashMap<String, Object>();
        payloadClaims.put(PublicClaims.SUBJECT, subject);
        payloadClaims.put(PublicClaims.EXPIRES_AT, epochSecond);
        if(bindClient){
            var hash = Murmur3.hash64(clientId.getBytes(StandardCharsets.UTF_8));
            payloadClaims.put(JwsCredential.CLIENT_HASH_LONG, hash);
        }
        bodyRender.accept(payloadClaims);
        return jws(payloadClaims);
    }


    public String jws(Map<String, Object> payloadClaims){
        String header = jwtHeader64;
        //if(bindClient){
        //    var hash = Murmur3.hash64(ServerContext.current().clientId().getBytes(StandardCharsets.UTF_8));
        //}
        String payload = Es256Signature.base64(JsonUtils.stringify(payloadClaims).getBytes(StandardCharsets.UTF_8));
        return signature.sign(header, payload);
    }

}


