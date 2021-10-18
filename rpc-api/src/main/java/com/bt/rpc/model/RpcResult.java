package com.bt.rpc.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 2020-01-02 17:21
 *
 * @author Martin.C
 */
@Data
public class RpcResult<Obj> implements Serializable {

    public static final int OK = 0;

    /**
     * google.rpc.Code/CommonCode 的超集<br>
     * 自定义业务异常码，以百为业务区间，大业务以千为区间
     */
    int code = 0 ;

    String message;

    Obj data;


    public boolean isSuccess(){
        return OK == code;
    }


    public static <T> RpcResult<T> success(T data){
        RpcResult<T> res = new RpcResult<>();
        res.data = data;
        //res.code = Code.OK;
        return res;
    }

    public static <T> RpcResult<T> error(int code, String msg) {
        RpcResult<T> res = new RpcResult<>();
        res.code = code;
        res.message = msg;
//        res.errors = Collections.singletonList(new CodeMsg(code, msg));
        return res;
    }
//
//    public void setCodeVal(int val){
//        code = Code.forNumber(val);
//    }

//    public int getCodeVal(){
//        return code.value;
//    }
}
