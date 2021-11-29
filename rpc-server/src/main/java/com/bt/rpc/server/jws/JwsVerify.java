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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 10:52 AM
 */
@Slf4j
public class JwsVerify implements CredentialVerify {

    public static final String WELL_KNOWN_JWKS_PATH = ".well-known/jwks.json";
    public static final String DEFAULT_COOKIE_NAME = "access-token";

    static final long GAP_MILL = 5 * 60 * 1000L;

    final Map<String, ECPublicKey> jwksCache = new ConcurrentHashMap<>();

    long lastAccess;

    final String url;

    final ExtVerify extVerify ;

    @Getter
    final String cookieName;

    //public JwsVerify(String url){
    //    this(url,DEFAULT_COOKIE_NAME);
    //}
    public JwsVerify(String url,String cookieName) {
        this(url,cookieName,ExtVerify.EMPTY);
    }

    public JwsVerify(String url,String cookieName,ExtVerify extVerify) {
        if (!url.endsWith(".json")) {
            url += url.endsWith("/") ? WELL_KNOWN_JWKS_PATH : "/" + WELL_KNOWN_JWKS_PATH;
        }
        this.url = url;
        this.cookieName = cookieName;
        this.extVerify = extVerify;
    }

    ECPublicKey useKey(String kid) {
        var key = jwksCache.get(kid);
        if (null != key) {
            return key;
        }
        loadJwks();
        return jwksCache.get(kid);
    }

    public String getUrl() {
        return url;
    }

    public void loadJwks() {
        if (System.currentTimeMillis() - lastAccess >= GAP_MILL) {
            try {
                lastAccess = System.currentTimeMillis();
                try (InputStream in = new URL(url).openStream()) {
                    var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    var jwks = JsonUtils.parse(json, Jwks.class);
                    for (var jwk : jwks.keys) {
                        if (Es256Jwk.ELLIPTIC_CURVE.equals(jwk.get(Jwks.KEY_TYPE))) {
                            var ecKey = new Es256Jwk(jwk);
                            jwksCache.put(ecKey.kid, ecKey.toECPublicKey());
                        }
                    }
                }
                log.info("success fetch jwks :  {} ", jwksCache.keySet());
            } catch (Exception e) {
                log.error("error fetch jwks :  " + url, e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     *     //    private final String audience;
     *     //
     *     //    AudienceValidator(String audience) {
     *     //        this.audience = audience;
     *     //    }
     *         //        if (jwt.getAudience().contains(audience)) {
     *     //            return OAuth2TokenValidatorResult.success();
     *     //        }
     */

    @Override
    public UserCredential verify(String token, String cid) throws StatusException {

        if (null == token || token.isBlank()) {
            throw Status.UNAUTHENTICATED.withDescription("requireCredential but empty token").asException();
        }
        var jws = new JwsCredential(token);

        var kid = jws.getKeyId();
        var key = useKey(kid);
        if (null == key) {
            throw Status.PERMISSION_DENIED.withDescription("kid  not found or expired : " + kid).asException();
        }

        var data = jws.jwtWithoutSign().getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getUrlDecoder().decode(jws.sign64());
        try {
            var valid = Es256Jwk.isValid(data, signature, key);
            if (valid) {
                jws.markSignValid();
                if (System.currentTimeMillis() / 1000L > jws.getExpiresAt().longValue()) {
                    throw Status.UNAUTHENTICATED.withDescription("Token expired at: " + jws.getExpiresAt()).asException();
                }

                extVerify.afterSignCheck(jws);

                return jws;
            }
            throw Status.PERMISSION_DENIED.withDescription("invalid signature !").asException();
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw Status.PERMISSION_DENIED.withCause(e).asException();
        }

    }

}