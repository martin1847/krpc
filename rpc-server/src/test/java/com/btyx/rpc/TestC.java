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
        
        var users = "[\n"
                + "{\"biz\":\"0411\",\"username\":\"0411test01\",\"sub\":\"b2970af4-850d-4e48-8add-578084f4c2c0\"},\n"
                + "{\"biz\":\"0411\",\"username\":\"0411test02\",\"sub\":\"eaa8edf2-1c92-4e7c-a4d3-ede26af1ac79\"},\n"
                + "{\"biz\":\"0411\",\"username\":\"0411test03\",\"sub\":\"bb9e1c67-ebdc-42bc-ac5c-26d2648dfacb\"},\n"
                + "{\"biz\":\"1411\",\"username\":\"1411test01\",\"sub\":\"df632926-2ec7-4f9d-848e-ad56b0654f46\"},\n"
                + "{\"biz\":\"1411\",\"username\":\"1411test02\",\"sub\":\"28af63e1-0df0-4861-b4a5-97f7fe30b510\"},\n"
                + "{\"biz\":\"1411\",\"username\":\"1411test03\",\"sub\":\"63e506a2-2a00-439d-a367-a4bf22a588f9\"},\n"
                + "{\"biz\":\"2411\",\"username\":\"2411test01\",\"sub\":\"4d896c4c-b8a0-46ad-afdf-9466391a9077\"},\n"
                + "{\"biz\":\"2411\",\"username\":\"2411test02\",\"sub\":\"b0572e69-ea56-4879-a96a-43b07892fb80\"},\n"
                + "{\"biz\":\"2411\",\"username\":\"2411test03\",\"sub\":\"503e6371-efff-48a0-b94d-75d9b24bf02d\"}\n"
                + "]\n";
        var list = (List<Map<String,Object>>)JsonUtils.parse(users, ArrayList.class);
        return list.stream().map(it->
            new UserJwt((String) it.remove("username"),it)
        ).collect(Collectors.toList());
    }
}