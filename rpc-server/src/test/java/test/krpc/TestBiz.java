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
public class TestBiz implements TestBase {

    public static void main(String[] args) {
        new TestBiz().genJwt();
    }

    @Override
    public String kid() {
        return "biz-jws-2201";
    }

    @Override
    public String priB64() {
        return "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAs0GafF_G0WP-ERFmn3QbLMDDMTpaDuiGgYGEKqTezJQ==";
    }

    @Override
    public List<UserJwt> users() {
        //zhuo.li 1035
        //lin.gao 1011
        //frank.liu 1036
        return Arrays.asList(
                new UserJwt("0411test","{\n"
                        + "  \"sub\": \"b191249b-9a0c-4a1d-9309-2b284d4b5a31\",\n"
                        + "  \"biz\": \"0411\""
                        + "}"),
                new UserJwt("1411test","{\n"
                        + "  \"sub\": \"66f4de38-c7cd-4003-8ebb-255c24cb47b3\",\n"
                        + "  \"biz\": \"1411\""
                        + "}"),
                new UserJwt("2411test","{\n"
                        + "  \"sub\": \"7badeca3-2920-43c3-bde5-c45d7a381c42\",\n"
                        + "  \"biz\": \"2411\""
                        + "}")
        );
    }
}