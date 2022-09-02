/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.btyx.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bt.rpc.util.JsonUtils;


/**
 *
 * @author martin.cong
 * @version 2022-04-12 17:58
 */
public class TestC implements TestBase {

    public static void main(String[] args) {
        
        new TestC().genJwt();
    }

    @Override
    public String kid() {
        return "jws-2201";
    }

    @Override
    public String priB64() {
        return "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCPl9g70vMXr6E0dwJ36IrPJjAURVb_1J455UK1H3y7WA==";
    }

    @Override
    public List<UserJwt> users() {
        //SELECT biz as biz ,username,bin_to_uuid(id,1) as sub FROM `user_account` WHERE username in 
        // ('0411test01','0411test02','0411test03', '1411test01','1411test02','1411test03', '2411test01','2411test02','2411test03' );
        //


        var users = "["
                + "{\"username\":\"135****746\",\"sub\":\"69887772860420\"},"
                + "{\"username\":\"156****268\",\"sub\":\"69887772827650\"},"
                + "{\"username\":\"176****076\",\"sub\":\"69887772860418\"},"
                + "{\"biz\":\"0411\",\"username\":\"0411test01\",\"sub\":\"69893636882455\"},"
                + "{\"biz\":\"0411\",\"username\":\"0411test02\",\"sub\":\"69893636882456\"},"
                + "{\"biz\":\"0411\",\"username\":\"0411test03\",\"sub\":\"69893636816909\"}"
                + "]";
        var list = (List<Map<String,Object>>)JsonUtils.parse(users, ArrayList.class);
        return list.stream().map(it->
            new UserJwt((String) it.remove("username"),it)
        ).collect(Collectors.toList());
    }
}