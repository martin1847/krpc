package com.btyx.test;

import java.util.List;
import java.util.Map;

import com.bt.model.PagedList;
import com.bt.model.PagedQuery;
import com.bt.rpc.annotation.Doc;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.model.RpcResult;
import com.btyx.test.dto.Img;
import com.btyx.test.dto.TimeReq;
import com.btyx.test.dto.TimeResult;
import com.btyx.test.dto.User;

/**
 * 2020-01-06 15:51
 *
 * @author Martin.C
 */
@RpcService(description = "这是个例子, DemoRpc，用来演示客户端调用")
public interface DemoRpc {

    //    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<String> save(User user);

    RpcResult<List<User>> listUser(List<User> query);
}
