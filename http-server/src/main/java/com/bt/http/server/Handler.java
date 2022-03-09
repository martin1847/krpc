/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.http.server;

import java.util.List;

import io.netty.handler.codec.http.HttpHeaders;

/**
 *
 * @author Martin.C
 * @version 2021/12/22 1:26 PM
 */
public interface Handler<ParamDTO> {


    byte[] EMPTY = new byte[0];

    String SUF = Handler.class.getSimpleName();

    default String path(){
        var name =  getClass().getSimpleName();
        int end;
        if((end = name.indexOf(SUF) ) > 0){
            name = name.substring(0,end);
        }
        char c = name.charAt(0);
        return "/"+ Character.toLowerCase(c) + name.substring(1);
    }

    byte[] handle(ParamDTO param, List<AsciiHeader> resHeader, HttpHeaders requestHeaders);


    default String contextType(){
        return null;// AbstractHttpHandler.TYPE_JSON;
    }
}