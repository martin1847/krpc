/**
 * ZLKJ.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.krpc.test.jwks.util;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 *
 * @author Martin.C
 * @version 2021/11/15 4:54 PM
 */
public class JwksReader {


    // https://connect2id.com/products/nimbus-jose-jwt/examples/jwk-retrieval
    public static void main(String[] args) throws IOException, ParseException {

        // HTTP connect timeout in milliseconds
        int connectTimeout = 100;

        // HTTP read timeout in milliseconds
        int readTimeout = 100;

        // JWK set size limit, in bytes
        int sizeLimit = 10000;

        // Load JWK set from filesystem
        //JWKSet localKeys = JWKSet.load(new File("my-key-store.json"));
        // The URL
        URL url = new URL("https://c2id.com/jwk-set.json");

        // Load JWK set from URL
        //JWKSet publicKeys = JWKSet.load(url, connectTimeout, readTimeout, sizeLimit);

        // Convert back to std Java interfaces
        //publicKey = jwk.toRSAPublicKey();
        //privateKey = jwk.toRSAPrivateKey();

        Date now = new Date();
        JWTClaimsSet jwtClaims = new JWTClaimsSet.Builder()
                .issuer("https://openid.net")
                .subject("alice")
                .audience(Arrays.asList("https://app-one.com", "https://app-two.com"))
                .expirationTime(new Date(now.getTime() + 1000*60*10)) // expires in 10 minutes
                .notBeforeTime(now)
                .issueTime(now)
                .jwtID(UUID.randomUUID().toString())
                .build();

        System.out.println(jwtClaims.toJSONObject());

        JWEHeader header = new JWEHeader(
                JWEAlgorithm.RSA_OAEP_256,
                EncryptionMethod.A128GCM
        );
    }
}