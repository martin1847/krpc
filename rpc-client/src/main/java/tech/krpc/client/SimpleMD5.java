package tech.krpc.client;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 2020-08-26 10:23
 *
 * @author Martin.C
 */
public abstract class SimpleMD5 {

    static final char[] DIGITS_LOWER = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd',
            'e', 'f' };

    static final char[] DIGITS_UPPER = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D',
            'E', 'F' };

    private static final String MD5 = "MD5";


    public static String md5(byte[] bytes){
        return md5(bytes,DIGITS_LOWER);
    }

    public static String md5Upper(byte[] bytes){
        return md5(bytes,DIGITS_UPPER);
    }

    private static String md5(byte[] bytes,char[] digitChars){
        try {
            var md5 =  MessageDigest.getInstance(MD5);
            var data = md5.digest(bytes);
            final int l = data.length;
            final char[] out = new char[l << 1];
            // two characters form the hex value.
            for (int i = 0, j = 0; i < l; i++) {
                out[j++] = digitChars[(0xF0 & data[i]) >>> 4];
                out[j++] = digitChars[0x0F & data[i]];
            }
            return new String(out);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
    }

//    public static void main(String[] args) {
//        for (int i = 0; i < 32; i++) {
//            System.out.println( i +"\t" + md5(Integer.valueOf(i).toString().getBytes()));
//
//        }
//    }
}
