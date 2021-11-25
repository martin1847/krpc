/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.bt.rpc.util.JsonUtils;
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


    static final Consumer<Map<String,Object>> EMPTY = map->{};

    private String         jwtHeader64;
    private Es256Signature signature;

    public Es256Jws(String kid, String ecPriBase64) {
        this.kid = kid;
        this.ecPriBase64 = ecPriBase64;
            //var headerJson = "{\"kid\":\"" + kid + "\",\"alg\":\"ES256\"}";
        var headers = new HashMap<String,String>(8);
        headers.put(PublicClaims.ALGORITHM,Es256Jwk.JWS_ALG);
        headers.put(PublicClaims.KEY_ID,kid);
        jwtHeader64 =  Es256Signature.base64(JsonUtils.stringify(headers).getBytes(StandardCharsets.UTF_8));
        signature = new Es256Signature(ecPriBase64);

        //System.out.println("JWT Test : ========== " + jwt("1234", "w-xxxx",
        //        true, Instant.now().getEpochSecond() + 3600));
    }

    public String jws(String subject, int epochSecond){
        return jws(subject,epochSecond,EMPTY);
    }

    public String jws(String subject, int epochSecond, Consumer<Map<String, Object>> bodyHanlder) {

        var payloadClaims = new HashMap<String, Object>();
        payloadClaims.put(PublicClaims.SUBJECT, subject);
        payloadClaims.put(PublicClaims.EXPIRES_AT, epochSecond);
        bodyHanlder.accept(payloadClaims);
        String header = jwtHeader64;
        String payload = Es256Signature.base64(JsonUtils.stringify(payloadClaims).getBytes(StandardCharsets.UTF_8));
        return signature.sign(header, payload);
    }

}


