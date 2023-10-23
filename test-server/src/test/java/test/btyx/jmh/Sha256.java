/**
 * Zhulinkeji.com Inc.
 * Copyright (c) 2021-2023 All Rights Reserved.
 */
package test.btrpc.jmh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.bt.rpc.util.MD5;
import com.google.common.hash.Hashing;

/**
 *
 * @author martin
 * @version 2023/10/09 11:18
 */
public class Sha256 {
    public static String jdk(byte[] b) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException var3) {
            throw new RuntimeException("SHA-256 is not supported." + var3.getMessage());
        }
        byte[] d = md.digest(b);

        return MD5.toLowerStrting(d);
    }

    public static String guava(byte[] data){
        return Hashing.sha256().hashBytes(data).toString();
    }

}