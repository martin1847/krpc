/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package test.krpc;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author martin.cong
 * @version 2022-04-12 17:58
 */
public class TestBackoffice implements TestBase {

    public static void main(String[] args) {
        new TestBackoffice().genJwt();
    }

    @Override
    public String kid() {
        return "bo-test-2111";
    }

    @Override
    public String priB64() {
        return "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBiUrtaBXje_VAEUdU2DlLlL27IVgf0_VzZdv90exUwgA==";
    }

    @Override
    public List<UserJwt> users() {
        //zhuo.li 1035
        //lin.gao 1011
        //frank.liu 1036
        return Arrays.asList(
                new UserJwt("zhuo.li","{\"sub\": \"1035\"}"),
                new UserJwt("lin.gao","{\"sub\": \"1011\"}"),
                new UserJwt("frank.liu","{\"sub\": \"1036\",\"adm\": 1}")
        );
    }
}