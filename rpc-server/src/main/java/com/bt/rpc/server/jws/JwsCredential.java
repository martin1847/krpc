/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bt.rpc.util.JsonUtils;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 9:55 AM
 */
public class JwsCredential implements UserCredential{



    static final char DOT = '.';

    private Map<String,String> header;
    private Map<String,Object> payload;


    private String header64,payload64,sign64;

    public JwsCredential(String token){
        var next = 0;
        header64 = token.substring(0,(next=token.indexOf(DOT)));
        header = JsonUtils.parse(decode(header64), HashMap.class);
        next++;
        payload64 = token.substring(next,(next=token.indexOf(DOT,next)));
        next++;
        sign64 = token.substring(next);
    }

    String jwtWithoutSign(){
        return header64+DOT+payload64;
    }

    String sign64(){
        return sign64;
    }

    void markSignValid(){
        payload = JsonUtils.parse(decode(payload64), HashMap.class);
    }


    public String getAlgorithm() {
        return header.get(PublicClaims.ALGORITHM);
    }


    public String getKeyId() {
        return header.get(PublicClaims.KEY_ID);
    }

    @Override
    public String getIssuer() {
        return (String) payload.get(PublicClaims.ISSUER);
    }

    @Override
    public String getSubject() {
        return (String) payload.get(PublicClaims.SUBJECT);
    }

    @Override
    public List<String> getAudience() {
        return (List<String>) payload.get(PublicClaims.AUDIENCE);
    }

    @Override
    public Number getExpiresAt() {
        return (Number) payload.get(PublicClaims.EXPIRES_AT);
    }

    @Override
    public Number getNotBefore() {
        return (Number) payload.get(PublicClaims.NOT_BEFORE);
    }

    @Override
    public Number getIssuedAt() {
        return (Number) payload.get(PublicClaims.ISSUED_AT);
    }

    @Override
    public String getId() {
        return (String) payload.get(PublicClaims.JWT_ID);
    }

    public Object get(String key){
        return payload.get(key);
    }


    static String decode(String part){
        return  new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }
}