/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.gen.meta;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 *
 * @author Martin.C
 * @version 2021/11/04 11:25 AM
 */
@AllArgsConstructor
@NoArgsConstructor
//@Data
public class Anno {

    @Getter
    String name;

    @Getter@Setter
    Map<String,Object> properties;

    public void setName(String name) {
        this.name = name;
        if(null == originName){
            originName = name;
        }
    }

    public String originName;
}