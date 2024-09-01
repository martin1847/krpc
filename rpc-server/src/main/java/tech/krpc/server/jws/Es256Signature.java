/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.server.jws;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Martin.C
 * @version 2021/11/17 3:37 PM
 */
@Slf4j
public class Es256Signature {

    final FastThreadLocal<Signature> sigLocal;

    public Es256Signature(String pri64) {
        ECPrivateKey privateKey;

        try {
            var bytes = Base64.getUrlDecoder().decode(pri64.trim());
            var privSpec = new PKCS8EncodedKeySpec(bytes);
            privateKey = (ECPrivateKey) KeyFactory.getInstance(Es256Jwk.ELLIPTIC_CURVE)
                    .generatePrivate(privSpec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("error get privateKey: " + pri64, e);
            throw new RuntimeException(e);
        }

        //sigLocal = ThreadLocal.withInitial(() -> {
        sigLocal = new FastThreadLocal<>() {
            @Override
            protected Signature initialValue(){
                try {
                    var sig = Signature.getInstance(Es256Jwk.SING_ALGORITHM);
                    sig.initSign(privateKey);
                    return sig;
                } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                    log.error("error get Signature: ", e);
                    throw new RuntimeException(e);
                }
            }
        };
    }

    public String sign(String header64, String payload64) {
        try {
            String jwtNoSign = header64 + '.' + payload64;
            var sig = sigLocal.get();
            sig.update(jwtNoSign.getBytes(StandardCharsets.UTF_8));
            var signature = Es256Jwk.der2joseConcat(sig.sign());
            return jwtNoSign + '.' + base64(signature);
        } catch (SignatureException e) {
            throw new RuntimeException(e);
        }
    }

    public static String base64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

}