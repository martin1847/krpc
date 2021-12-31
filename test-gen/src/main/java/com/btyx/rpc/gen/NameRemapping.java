/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc.gen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bt.rpc.common.meta.Dto;
import com.bt.rpc.common.meta.PropertyType;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 6:23 PM
 */
public class NameRemapping {


    static Map<String,String> nameMapping = new HashMap<>();

    static {
        nameMapping.put("String","string");
        nameMapping.put("Integer","number");
        nameMapping.put("Long","number");
        nameMapping.put("Float","number");
        nameMapping.put("Double","number");
        nameMapping.put("Boolean","boolean");
        nameMapping.put("List","Array");
        nameMapping.put("byte[]","Uint8Array");
        nameMapping.put("Object","T");
    }


    void remapping(Dto dto){
        var newName =nameMapping.get(dto.getName());
        if(null != newName){
            dto.setName(newName);
        }
        if(dto.hasChild()){
            dto.getFields().forEach(f->{
                remapping(f.getType().getRawType());
                remapping(f.getType().getGenerics());
            });
        }
    }
    void remapping(PropertyType gen){
        remapping(gen.getRawType());
        remapping(gen.getGenerics());
    }

    void remapping(List<PropertyType> gens){
        if(null == gens || gens.isEmpty()){
            return;
        }
        gens.forEach(this::remapping);
    }

}