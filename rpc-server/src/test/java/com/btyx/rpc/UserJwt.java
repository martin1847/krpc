/**
 * BestULearn.com Inc.
 * Copyright (c) 2021-2022 All Rights Reserved.
 */
package com.btyx.rpc;

import java.util.HashMap;
import java.util.Map;

import com.bt.rpc.util.JsonUtils;

/**
 *
 * @author martin.cong
 * @version 2022-04-12 18:05
 */

public class UserJwt {
    String userName;
    Map<String,Object> claims;

    public UserJwt(String userName, Map<String, Object> claims) {
        this.userName = userName;
        this.claims = claims;
    }

    public UserJwt(String userName, String jsonClaims) {
        this.userName = userName;
        this.claims = JsonUtils.parse(jsonClaims, HashMap.class);
    }

    public String getUserName() {
        return userName;
    }
    public void setUserName(String userName) {
        this.userName = userName;
    }
    public Map<String, Object> getClaims() {
        return claims;
    }

    public void setClaims(Map<String, Object> claims) {
        this.claims = claims;
    }
}