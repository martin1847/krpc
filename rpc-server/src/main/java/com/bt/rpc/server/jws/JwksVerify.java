/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bt.rpc.util.JsonUtils;
import io.grpc.Status;
import io.grpc.StatusException;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 10:52 AM
 */
@Slf4j
public class JwksVerify implements CredentialVerify {

    static final String WELL_KNOWN_JWKS_PATH = ".well-known/jwks.json";

    static final long GAP_MILL = 5*60*1000L;

    final Map<String, ECPublicKey> jwksCache = new ConcurrentHashMap<>();

    long lastAccess;

    String url;

    public JwksVerify(String url) {
        if(!url.endsWith(".json")){
            url += url.endsWith("/")? WELL_KNOWN_JWKS_PATH : "/"+WELL_KNOWN_JWKS_PATH;
        }
        this.url = url;

        initJwks();
    }

    ECPublicKey useKey(String kid)  {
        var key = jwksCache.get(kid);
        if(null != key){
            return key;
        }
        initJwks();
        return jwksCache.get(kid);
    }

    void initJwks() {
        if(System.currentTimeMillis() - lastAccess >= GAP_MILL){
            try{
                lastAccess = System.currentTimeMillis();
                try (InputStream in = new URL(url).openStream()) {
                    var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    var jwks = JsonUtils.parse(json,Jwks.class);
                    for(var jwk : jwks.keys){
                        if( Es256Jwk.ELLIPTIC_CURVE.equals(jwk.get(Jwks.KEY_TYPE))){
                            var ecKey = new Es256Jwk(jwk);
                            jwksCache.put(ecKey.kid,ecKey.toECPublicKey());
                        }
                    }
                }
                log.info("success fetch jwks :  {} ", jwksCache.keySet());
            }catch (Exception e){
                log.error("error fetch jwks :  "+ url,e);
                throw new RuntimeException(e);
            }
        }
    }



    public UserCredential verify(String token,String cid) throws StatusException{

        if(null == token || token.isBlank()){
            throw Status.UNAUTHENTICATED.withDescription("requireCredential but empty token").asException();
        }
        var jws = new JwsCredential(token);

        var kid = jws.getKeyId();
        var key = useKey(kid);
        if(null == key){
            throw Status.PERMISSION_DENIED.withDescription( "kid  not found or expired : " + kid).asException();
        }

        var data = jws.jwtWithoutSign().getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getUrlDecoder().decode(jws.sign64());
        try {
            var valid =  Es256Jwk.isValid(data,signature,key);
            if(valid){
                //TODO check cid
                jws.markValid();
                if(System.currentTimeMillis()/1000L > jws.getExpiresAt().longValue()){
                    throw Status.UNAUTHENTICATED.withDescription("Token expired at: " + jws.getExpiresAt()).asException();
                }
                return jws;
            }
            throw Status.PERMISSION_DENIED.withDescription( "invalid signature !").asException();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw Status.PERMISSION_DENIED.withCause(e).asException();
        }

    }

}