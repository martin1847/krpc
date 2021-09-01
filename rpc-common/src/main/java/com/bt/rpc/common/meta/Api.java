package com.bt.rpc.common.meta;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class Api {
    String name;
    List<Method> methods;

    String description;

    public Api(String name, List<Method> methods, String description) {
        this.name = name;
        this.methods = methods;
        this.description = description;
    }

}