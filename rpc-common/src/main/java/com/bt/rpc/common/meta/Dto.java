package com.bt.rpc.common.meta;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class Dto {


    /// classSimpleName
    String name;

    int generics;
    // 是否做为入参
    boolean input;

    List<Property> fields;
    public boolean hasChild(){
        return  null != fields && ! fields.isEmpty();
    }

    public Dto(String name, int generics, boolean input) {
        this.name = name;
        this.generics = generics;
        this.input = input;
    }
}