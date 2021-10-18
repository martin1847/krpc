package com.bt.rpc.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 2020-01-02 17:21
 *
 * @author Martin.C
 */
@Data
public class RpcResult<DTO> implements Serializable {

    public static final int OK = 0;

    /**
     * google.rpc.Code/CommonCode 的超集<br>
     * 自定义业务异常码，以百为业务区间，大业务以千为区间<br>
     * 除非必要，禁止使用java Exception传递错误信息，请定义异常码
     */
    int code = 0 ;

    String message;

    DTO data;


    public boolean isOk(){
        return OK == code;
    }


    public static <T> RpcResult<T> success(T data){
        RpcResult<T> res = new RpcResult<>();
        res.data = data;
        return res;
    }

    public static <T> RpcResult<T> error(int code, String msg) {
        RpcResult<T> res = new RpcResult<>();
        res.code = code;
        res.message = msg;
        return res;
    }

}
