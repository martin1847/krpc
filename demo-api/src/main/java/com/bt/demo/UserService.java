/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.demo;

import com.bt.demo.dto.User;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;

/**
 *
 * @author Martin.C
 * @version 2021/10/27 10:52 AM
 */
@RpcService
public interface UserService {

    RpcResult<User> getUser(Integer id);

    RpcResult<Integer> createUser(User u);

}