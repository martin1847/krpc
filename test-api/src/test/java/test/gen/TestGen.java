/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package test.gen;

import com.bt.rpc.gen.Gen;
import com.testbt.DemoRpc;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 6:08 PM
 */
public class TestGen {


    public static void main(String[] args) {
        Gen.basePkg = DemoRpc.class.getPackageName();
        ////Gen.basePkg = "com.zlkj.course.partner.admin.service";
        //
        //var json  = "{\"1-1-1\":[\"1000-01-01\",\"2081-05-08\"]}";
        //
        //var map = JsonUtils.parse(json, HashMap.class);
        //System.out.println(map);

        Gen.genSingleTypescript("-test", DemoRpc.class);
        Gen.genTypescript("demo-java-server");
        Gen.genMiniprogram("demo-java-server");
        Gen.genDart("demo-java-server");
        ////Gen.genYamltest("demo-java-server");
        //
        //testEmojiChar();
    }

    static void testEmojiChar(){
        char c = '1';
        var str = "\uD83D\uDE08";
        String s = "😈";
        //char cg = '😈';
        char cz = '中';
        System.out.println(s.equals(str));
        System.out.println((int)c);
        System.out.println(str);
        System.out.println(str.length());
        System.out.println(str.charAt(0) + " \t \u3403 "+ str.codePointAt(0));


        var estr="\uD83D\uDC69\u200D\uD83D\uDCBB❤️\uD83C\uDF75 is \uD83D\uDC69\u200D\uD83D\uDCBB(5) + ❤️(2) + \uD83C\uDF75(2) = 9.";
        System.out.println(estr);
        System.out.println(new StringBuilder(estr).reverse());

        int charMax = 65535;
        for (int i = charMax; i <= charMax + 16; i++) {
            System.out.print(i +"\t");
            char it = (char) i;
            System.out.println(it +" : "+ ((int)it));
        }
    }
}