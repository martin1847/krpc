/**
 * ZLKJ.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.krpc.test.jwks;

import java.security.NoSuchAlgorithmException;

import com.krpc.test.jwks.util.ES256Gen;
import com.krpc.test.jwks.util.KeyPairGen;
import com.krpc.test.jwks.util.RsaGen;

/**
 *
 * @author Martin.C
 * @version 2021/11/15 3:40 PM
 */
public class NewKey {

    public static void main(String[] args) throws NoSuchAlgorithmException {
        // Generate the RSA key pair

        var name = "jy-pwd";

        KeyPairGen gen = new RsaGen(name,false);

        gen.gen();
        //
        name = "jy-jws";

        gen = new ES256Gen(name,true);

        gen.gen();



    }

}