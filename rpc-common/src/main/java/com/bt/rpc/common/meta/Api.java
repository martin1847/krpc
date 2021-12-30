package com.bt.rpc.common.meta;

import com.bt.rpc.util.RefUtils;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

//暴露出的RpcService
@Data
@NoArgsConstructor
public class Api {
    String       name;
    List<Method> methods;

    String description;

    Boolean web;

    public Api(String name, List<Method> methods, String description) {
        this.name = name.substring(name.indexOf('/') + 1);
        this.methods = methods;
        this.description = description;
        web = name.charAt(0) != RefUtils.HIDDEN_SERVICE;
    }

}