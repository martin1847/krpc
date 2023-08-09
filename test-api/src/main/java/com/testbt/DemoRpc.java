package com.testbt;

import java.util.List;

import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;
import com.testbt.dto.Book;
import com.testbt.dto.TimeReq;
import com.testbt.dto.TimeResult;

/**
 * 2020-01-06 15:51
 *
 * @author Martin.C
 */
@RpcService(description = "这是个例子, DemoRpc，用来演示客户端调用")
public interface DemoRpc {

    //    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<String> save(Book book);

    RpcResult<List<Book>> listUser(List<Book> query);
}
