package tech.krpc.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 2021-08-26 10:23
 *
 * @author martin
 */
public abstract class MD5 {

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
            //final int l = data.length;
            //final char[] out = new char[l << 1];
            //// two characters form the hex value.
            //for (int i = 0, j = 0; i < l; i++) {
            //    out[j++] = digitChars[(0xF0 & data[i]) >>> 4];
            //    out[j++] = digitChars[0x0F & data[i]];
            //}
            return toStrting(data,digitChars);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String toLowerStrting(byte[] digested){
        return toStrting(digested,DIGITS_LOWER);
    }
    public static String toStrting(byte[] digested,char[] digitChars){
        final int l = digested.length;
        final char[] out = new char[l << 1];
        //var digitChars = DIGITS_LOWER;
        // two characters form the hex value.
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = digitChars[(0xF0 & digested[i]) >>> 4];
            out[j++] = digitChars[0x0F & digested[i]];
        }
        return new String(out);
    }

    //
    //public static void main(String[] args) {
    //    for (int i = 0; i < 32; i++) {
    //        //echo -n 1 | md5
    //        // c4ca4238a0b923820dcc509a6f75849b
    //        System.out.println( i +"\t" + md5(Integer.valueOf(i).toString().getBytes()));
    //
    //    }
    //}
}

