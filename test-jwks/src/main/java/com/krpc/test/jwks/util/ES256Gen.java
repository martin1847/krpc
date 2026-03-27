/**
 * ZLKJ.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.krpc.test.jwks.util;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;

/**
 *   | ES256        | ECDSA using P-256 and SHA-256 | Recommended+
 *   https://datatracker.ietf.org/doc/html/rfc7518#section-3
 *
 *   Elliptic Curve Digital Signature Algorithm (ECDSA)
 *
 * @author Martin.C
 * @version 2021/11/15 4:25 PM
 */
public class ES256Gen extends KeyPairGen{


    public ES256Gen(String kidName, boolean useForSignature) {
        super(kidName, useForSignature);
    }

    @Override
    protected KeyPairGenerator make() throws NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        var gen = KeyPairGenerator.getInstance("EC");
        //var gen = KeyPairGenerator.getInstance("ECDH");
        gen.initialize(Curve.P_256.toECParameterSpec());
        return gen;
    }

    @Override
    protected JWK jwk(KeyPair keyPair,String kid) {
        return new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                .privateKey( keyPair.getPrivate()) //(ECPrivateKey)
                .keyUse( useForSignature?  KeyUse.SIGNATURE : KeyUse.ENCRYPTION)
                .keyID(kid)
                .build();
    }
}