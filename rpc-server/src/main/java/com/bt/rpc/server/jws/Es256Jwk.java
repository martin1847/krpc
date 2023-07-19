/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.server.jws;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Base64;
import java.util.Map;

/**
 *
 * @author Martin.C
 * @version 2021/11/16 7:32 PM
 */

public class Es256Jwk {

    public static final String ELLIPTIC_CURVE_TYPE_P256 = "P-256";
    public static final String ELLIPTIC_CURVE_TYPE_P384 = "P-384";
    public static final String ELLIPTIC_CURVE_TYPE_P521 = "P-521";
    public static final String SING_ALGORITHM = "SHA256withECDSA";
    public static final String ELLIPTIC_CURVE = "EC";
    public static final String JWS_ALG = "ES256";

    //for ES256; ES384->96 ; ES512->132
    public static final int SIGNATURE_BYTES_LENGTH = 64;

    String kid;
    String x;
    String y;
    String crv;

    /**
     *     {
     *       "kty": "EC",
     *       "use": "sig",
     *       "crv": "P-256",
     *       "kid": "bo-test-2111",
     *       "x": "RhdqCsq1MooCjBiOrliZInAii8fdf4-3jOT0pRpohus",
     *       "y": "6s9ECrJurlHCkSx8CTnqhS5HN7h9-dblFgLfpRPcPeg"
     *     }
     */
    public Es256Jwk(Map<String,String> key) {
        this(
                key.get(PublicClaims.KEY_ID),
                key.get("x"),
                key.get("y"),
                key.get("crv")
        );
    }

    public Es256Jwk(String kid, String x, String y, String crv) {
        this.kid = kid;
        this.x = x;
        this.y = y;
        this.crv = crv;
    }

    public ECPublicKey toECPublicKey() throws NoSuchAlgorithmException, InvalidParameterSpecException, InvalidKeySpecException {
        KeyFactory keyFactory = KeyFactory.getInstance(ELLIPTIC_CURVE);
        ECPoint ecPoint = new ECPoint(new BigInteger(1,Base64.getUrlDecoder().decode(x)),
                new BigInteger(1,Base64.getUrlDecoder().decode(y)));
        AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance(ELLIPTIC_CURVE);

        String curve = crv;
        switch (curve) {
            case ELLIPTIC_CURVE_TYPE_P256 :
                algorithmParameters.init(new ECGenParameterSpec("secp256r1"));
                break;
            case ELLIPTIC_CURVE_TYPE_P384 :
                algorithmParameters.init(new ECGenParameterSpec("secp384r1"));
                break;
            case ELLIPTIC_CURVE_TYPE_P521 :
                algorithmParameters.init(new ECGenParameterSpec("secp521r1"));
                break;
            default : throw new UnsupportedOperationException("Invalid or unsupported curve type " + curve);
        }
        ECParameterSpec ecParameterSpec = algorithmParameters.getParameterSpec(ECParameterSpec.class);
        ECPublicKeySpec ecPublicKeySpec = new ECPublicKeySpec(ecPoint, ecParameterSpec);
        return (ECPublicKey) keyFactory.generatePublic(ecPublicKeySpec);
    }


    static boolean isValid(byte[] data, byte[] signature, ECPublicKey key)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        var sig =  Signature.getInstance(SING_ALGORITHM);
        int expectedSize = SIGNATURE_BYTES_LENGTH;
        byte[] derSignature = expectedSize != signature.length && signature[0] == 0x30 ? signature : jws2der(signature);
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(derSignature);
    }


    /**
     * Transcodes the JCA ASN.1/DER-encoded signature into the concatenated
     * R + S format expected by ECDSA JWS.
     *
     * @param derSignature The ASN1./DER-encoded. Must not be {@code null}.
     */
    public static byte[] der2joseConcat(final byte[] derSignature) {


        // DER Structure: http://crypto.stackexchange.com/a/1797
        if (derSignature.length < 8 || derSignature[0] != 48) {
            throw new UnsupportedOperationException("Invalid ECDSA signature format");
        }

        int offset;
        if (derSignature[1] > 0) {
            offset = 2;
        } else if (derSignature[1] == (byte) 0x81) {
            offset = 3;
        } else {
            throw new UnsupportedOperationException("Invalid ECDSA signature format");
        }

        byte rLength = derSignature[offset + 1];

        int i = rLength;
        while ((i > 0) && (derSignature[(offset + 2 + rLength) - i] == 0)) {
            i--;
        }

        byte sLength = derSignature[offset + 2 + rLength + 1];

        int j = sLength;
        while ((j > 0) && (derSignature[(offset + 2 + rLength + 2 + sLength) - j] == 0)) {
            j--;
        }

        int rawLen = Math.max(i, j);
        rawLen = Math.max(rawLen, SIGNATURE_BYTES_LENGTH / 2);

        if ((derSignature[offset - 1] & 0xff) != derSignature.length - offset
                || (derSignature[offset - 1] & 0xff) != 2 + rLength + 2 + sLength
                || derSignature[offset] != 2
                || derSignature[offset + 2 + rLength] != 2) {
            throw new UnsupportedOperationException("Invalid ECDSA signature format");
        }

        final byte[] concatSignature = new byte[2 * rawLen];

        System.arraycopy(derSignature, (offset + 2 + rLength) - i, concatSignature, rawLen - i, i);
        System.arraycopy(derSignature, (offset + 2 + rLength + 2 + sLength) - j, concatSignature, 2 * rawLen - j, j);

        return concatSignature;
    }


    /**
     * Transcodes the ECDSA JWS signature into ASN.1/DER format for use by
     * the JCA verifier.
     *
     * @param jwsSignature The JWS signature, consisting of the
     *                     concatenated R and S values. Must not be
     *                     {@code null}.
     * @return The ASN.1/DER encoded signature.
     */
    public static byte[] jws2der(byte[] jwsSignature)  {

        int rawLen = jwsSignature.length / 2;

        int i = rawLen;

        while ((i > 0) && (jwsSignature[rawLen - i] == 0)) {
            i--;
        }

        int j = i;

        if (jwsSignature[rawLen - i] < 0) {
            j += 1;
        }

        int k = rawLen;

        while ((k > 0) && (jwsSignature[2 * rawLen - k] == 0)) {
            k--;
        }

        int l = k;

        if (jwsSignature[2 * rawLen - k] < 0) {
            l += 1;
        }

        int len = 2 + j + 2 + l;

        if (len > 255) {
            throw new UnsupportedOperationException("Invalid ECDSA signature format");
        }

        int offset;

        final byte derSignature[];

        if (len < 128) {
            derSignature = new byte[2 + 2 + j + 2 + l];
            offset = 1;
        } else {
            derSignature = new byte[3 + 2 + j + 2 + l];
            derSignature[1] = (byte) 0x81;
            offset = 2;
        }

        derSignature[0] = 48;
        derSignature[offset++] = (byte) len;
        derSignature[offset++] = 2;
        derSignature[offset++] = (byte) j;

        System.arraycopy(jwsSignature, rawLen - i, derSignature, (offset + j) - i, i);

        offset += j;

        derSignature[offset++] = 2;
        derSignature[offset++] = (byte) l;

        System.arraycopy(jwsSignature, 2 * rawLen - k, derSignature, (offset + l) - k, k);

        return derSignature;
    }

}