/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package test.gen;

import java.io.IOException;

import com.btyx.rpc.gen.Gen;
import freemarker.template.TemplateException;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 6:08 PM
 */
public class TestFtl {
    public static void main(String[] args) throws TemplateException, IOException {
        //Gen.genTypescript("demo-java-server",null);
        Gen.genDart("demo-java-server",null);
    }
}