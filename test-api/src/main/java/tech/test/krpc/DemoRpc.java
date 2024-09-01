package tech.test.krpc;

import java.util.List;

import tech.krpc.annotation.RpcService;
import tech.krpc.model.RpcResult;
import tech.test.krpc.dto.Book;
import tech.test.krpc.dto.TimeReq;
import tech.test.krpc.dto.TimeResult;

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
