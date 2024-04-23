package com.bt.rpc.model;

import java.io.Serializable;
import java.util.NoSuchElementException;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 2020-01-02 17:21
 *
 * @author Martin.C
 */
@Data
public class RpcResult<DTO> implements Serializable {

    public static final int OK = 0;
    public static final int DATA_LOSS = 15;

    /**
     * google.rpc.Code/CommonCode 的超集<br>
     * 自定义业务异常码，以百为业务区间，大业务以千为区间<br>
     * 除非必要，禁止使用java Exception传递错误信息，请定义异常码<br>
     * 最小0（msg/data逻辑互斥），不支持负值
     */
    int code = 0 ;

    /**
     * code > 0 的时候有值，不为null
     */
    String msg;

    /**
     * code == 0 的时候有值，不为null
     */
    DTO data;


    @JsonIgnore
    public boolean isOk(){
        return OK == code;
    }

    @JsonIgnore
    public <T> RpcResult<T> error() {
        return (RpcResult<T>) this;
    }


    public <T> RpcResult<T> ifOk(Function<DTO,T> dataHandler) {
        if( OK == code ){
            var res = dataHandler.apply(data);
            if(null != res) {
                return RpcResult.ok(res);
            }
            return RpcResult.error(DATA_LOSS,"call ifOk But got null !!!");
        }
        return (RpcResult<T>) this;
    }


    public DTO orElseThrow() {
        if (data == null) {
            throw new IllegalStateException("error code: "+code +" ,caused by: "+msg);
        }
        return data;
    }


    public static <T> RpcResult<T> ok(T data){
        RpcResult<T> res = new RpcResult<>();
        res.data = data;
        return res;
    }

    public static <T> RpcResult<T> error(int code, String msg) {
        RpcResult<T> res = new RpcResult<>();
        res.code = code;
        res.msg = msg;
        return res;
    }

    public static <T> RpcResult<T> error(CommonCode it) {
        RpcResult<T> res = new RpcResult<>();
        res.code = it.value;
        res.msg = it.name();
        return res;
    }

    public static <T> RpcResult<T> error(RpcResult<?> error) {
        return (RpcResult<T>) error;
    }

}
