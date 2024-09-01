/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.jws;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tech.krpc.util.JsonUtils;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 9:55 AM
 */
public class JwsCredential implements UserCredential{


    public static final char DOT = '.';

    public static final String CLIENT_HASH_LONG ="chl";

    private Map<String,String> header;
    private Map<String,Object> payload;


    public final String header64,payload64,sign64;

    public JwsCredential(String token){
        var next = 0;
        header64 = token.substring(0,(next=token.indexOf(DOT)));
        header = JsonUtils.parse(decode64(header64), HashMap.class);
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

    void parsePayload(){
        payload = JsonUtils.parse(decode64(payload64), HashMap.class);
    }

    public Map<String, Object> getPayload() {
        return payload;
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


    // Jackson for number > Integer.MAX then Long , otherwise Integer
    public Number getClientHashLong(){
        return (Number) payload.get(CLIENT_HASH_LONG);
    }

    static String decode64(String part){
        return  new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }
}