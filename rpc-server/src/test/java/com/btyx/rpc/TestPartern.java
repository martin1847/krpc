/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.btyx.rpc;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author martin.cong
 * @version 2022-04-12 17:58
 */
public class TestPartern implements TestBase {

    public static void main(String[] args) {
        new TestPartern().genJwt();
    }

    @Override
    public String kid() {
        return "pt-jws-2111";
    }

    @Override
    public String priB64() {
        return "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAwZ4M5x_znqFhn6g7wUSaAi2cYl6IxgGo5oPRP1JjW1w==";
    }

    @Override
    public List<UserJwt> users() {
        //zhuo.li 1035
        //lin.gao 1011
        //frank.liu 1036
        return Arrays.asList(
                new UserJwt("ptest01","{\n"
                        + "  \"sub\": \"b1c30965-2ed5-4b38-a270-e7d258bef631\",\n"
                        + "  \"pid\": 94,\n"
                        + "  \"rid\": 0\n"
                        + "}"),
                new UserJwt("ptest02","{\n"
                        + "  \"sub\": \"f62e9a73-ba08-46b2-9100-d086e6327e6a\",\n"
                        + "  \"pid\": 95,\n"
                        + "  \"rid\": 0\n"
                        + "}"),
                new UserJwt("ptest03","{\n"
                        + "  \"sub\": \"8d424bc6-210b-4aaa-b5ce-5715b765f7fc\",\n"
                        + "  \"pid\": 96,\n"
                        + "  \"rid\": 0\n"
                        + "}")
        );
    }
}