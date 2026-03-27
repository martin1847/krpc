/**
 * ZLKJ.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.krpc.test.jwks.util;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Base64;

import com.nimbusds.jose.jwk.JWK;

/**
 *
 * @author Martin.C
 * @version 2021/11/15 4:10 PM
 */
public abstract class KeyPairGen {


    String kidName;

    protected boolean useForSignature;

    public KeyPairGen(String kidName, boolean useForSignature) {
        this.useForSignature = useForSignature;

        //LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        var now  = LocalDate.now();
        var monthValue = now.getMonthValue();

        this.kidName = kidName + "-"+(now.getYear()-2000)+(monthValue < 10 ? "0" : "")+monthValue;
    }

    protected abstract KeyPairGenerator make() throws Exception;

    protected abstract JWK jwk(KeyPair keyPair,String kid);


    public void gen(){
        //var name = "rsa2048";
        //KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        //gen.initialize(2048);
        KeyPair keyPair = null;
        try {
            keyPair = make().generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        var pubKey = keyPair.getPublic();//(RSAPublicKey)
        var priKey =keyPair.getPrivate();// (RSAPrivateKey)

        //pubKey : X.509
        //System.out.println("pubKey : " + pubKey.getFormat());
        //
        ////priKey : PKCS#8
        //System.out.println("priKey : " + priKey.getFormat());


        var pub64 = Base64.getUrlEncoder().encodeToString(pubKey.getEncoded());
        var pubId = kidName + ".b64.x509.pub";
        System.out.println(pubId);
        System.out.println(pub64);
        System.out.println();

        var pri64 = Base64.getUrlEncoder().encodeToString(priKey.getEncoded());
        System.out.println("# base64.pkcs8.pri");
        System.out.println(kidName);
        System.out.println(pri64);

        System.out.println();

        var jwk = jwk(keyPair,kidName);
        // Output the private and public RSA JWK parameters
        System.out.println("/.well-known/jwks.json");
        System.out.println(String.format("{"
                + "\"keys\": [%s]"
                + "}",jwk.toPublicJWK()));

        System.out.println("----------");
        System.out.println();
    }

}