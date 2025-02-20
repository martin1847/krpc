/**
 * ZLKJ.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.krpc.test.jwks.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

/**
 *
 * @author Martin.C
 * @version 2021/11/15 4:25 PM
 */
public class RsaGen  extends KeyPairGen {


    public RsaGen(String kidName, boolean useForSignature) {
        super(kidName, useForSignature);
    }

    @Override
    protected KeyPairGenerator make() throws NoSuchAlgorithmException {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        return gen;
    }

    @Override
    protected JWK jwk(KeyPair keyPair,String kid) {
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .keyUse( useForSignature?  KeyUse.SIGNATURE : KeyUse.ENCRYPTION)
                .keyID(kid)
                .build();
    }
}