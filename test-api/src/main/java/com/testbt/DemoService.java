package com.testbt;

import com.bt.model.PagedList;
import com.bt.model.PagedQuery;
import com.bt.rpc.annotation.Doc;
import com.bt.rpc.annotation.UnsafeWeb;
import com.bt.rpc.annotation.RpcService;
import com.bt.rpc.model.RpcResult;
import com.testbt.dto.Book;
import com.testbt.dto.Img;
import com.testbt.dto.TimeReq;
import com.testbt.dto.TimeResult;

import java.util.List;
import java.util.Map;

/**
 * 2020-01-06 15:51
 *
 * @author Martin.C
 */
@UnsafeWeb
@RpcService(description = "这是个例子, Demo，用来演示客户端调用")
public interface DemoService {

    //    @Cached
    RpcResult<TimeResult> hello(TimeReq req);

    RpcResult<String> save(Book book);

    RpcResult<byte[]> bytesTime();

    // TS / Dart 客户端不支持 byte[] 做为入参
    RpcResult<byte[]> incBytes(byte[] bytes);

    RpcResult<Integer> bytesSum(byte[] bytes);

    @Deprecated
    RpcResult<String> str(String hello);

    // 只是做为功能测试，生产环境不要返回Map
    RpcResult<Map<String,Integer>> testMap();

    RpcResult<Integer> inc100(Integer i);

    @Doc("调用这个接口服务端会抛出RuntimeException")
    RpcResult<Integer> testRuntimeException();

    RpcResult<List<Integer>> wordLength(List<String> list);

    // 用来测试范型
    RpcResult<PagedList<Book>> plistBk(PagedQuery<Book> query);

    // 用来测试范型
    RpcResult<List<Book>> plistBk2(PagedQuery<Book> query);

    // 用来测试范型
    RpcResult<List<Book>> listBk(List<Book> query);

    // 用来测试范型
    RpcResult<PagedList<Book>> listBk2(List<Book> query);

    // 用来测试范型
    RpcResult<PagedList<Integer>> listInt(PagedQuery<Integer> query);

    RpcResult<Integer> sleep(Integer i);

    @Doc("软异常：返回逻辑错误，不泡异常")
    RpcResult<Integer> testLogicError(Integer i);

    default RpcResult<Integer> saveImg(Img img){
        return null;
    }
}
