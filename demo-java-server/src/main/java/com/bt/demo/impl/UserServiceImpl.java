/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.demo.impl;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.bt.dao.mapper.UserMapper;
import com.bt.demo.UserService;
import com.bt.demo.dto.User;
import com.bt.rpc.model.RpcResult;
import io.quarkus.runtime.Startup;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 10:55 AM
 */
@ApplicationScoped
@Startup
public class UserServiceImpl implements UserService {

    @Inject
    UserMapper userMapper;


    @Override
    public RpcResult<User> getUser(Integer id) {
        return RpcResult.ok(userMapper.getUser(id));
    }

    @Override
    public RpcResult<Integer> createUser(User u) {
        return null;
    }
}