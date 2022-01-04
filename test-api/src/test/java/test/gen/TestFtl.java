/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package test.gen;

import com.btyx.rpc.gen.Gen;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 6:08 PM
 */
public class TestFtl {
    public static void main(String[] args) {
        Gen.genTypescript("demo-java-server");
        Gen.genDart("demo-java-server");
    }
}