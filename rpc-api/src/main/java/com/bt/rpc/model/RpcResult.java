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

    // default value , same with c# side
    Code code = Code.OK;

    String message;

    Obj data;


    public boolean isSuccess(){
        return Code.OK == code;
    }


    public static <T> RpcResult<T> success(T data){
        RpcResult<T> res = new RpcResult<>();
        res.data = data;
        //res.code = Code.OK;
        return res;
    }

    public static <T> RpcResult<T> error(Code code, String msg) {
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
