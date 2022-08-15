/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc.gen;

import java.util.HashMap;
import java.util.Map;

import javax.validation.constraints.Email;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Negative;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Null;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.bt.rpc.annotation.Doc;
import com.btyx.rpc.gen.meta.Anno;
import com.google.common.base.Function;

/**
 *
 * @author Martin.C
 * @version 2021/12/31 3:00 PM
 */
public class TsNameMap {

    final static Map<String,String> nameMapping = new HashMap<>();

    static {
        nameMapping.put("String","string");
        nameMapping.put("Integer","number");
        nameMapping.put("Long","number");
        nameMapping.put("Float","number");
        nameMapping.put("Double","number");
        nameMapping.put("Boolean","boolean");
        nameMapping.put("List","Array");
        nameMapping.put("Date","number");
        nameMapping.put("byte[]","Array<number>");
        nameMapping.put("int[]","Array<number>");
    }

    final static Map<Class, Function<Anno,String>> annoMapping = new HashMap<>();

    static {
        annoMapping.put(Doc.class, it->"/// "+it.getProperties().get("value"));
        annoMapping.put(Deprecated.class,it->"/// Deprecated");
        annoMapping.put(Null.class, it->"@IsOptional()");
        annoMapping.put(NotNull.class, it->"@IsDefined()");
        annoMapping.put(NotEmpty.class, it->"@IsNotEmpty()");
        annoMapping.put(NotBlank.class, it->"@MinLength(1)");
        annoMapping.put(Positive.class, it->"@IsPositive()");
        annoMapping.put(Negative.class, it->"@IsNegative()");
        annoMapping.put(Email.class, it->"@IsEmail()");
        annoMapping.put(Min.class, it->"@Min("+it.getProperties().get("value")+")");
        annoMapping.put(Max.class, it->"@Max("+it.getProperties().get("value")+")");
        annoMapping.put(Size.class, anno->{
            Integer min = (Integer)anno.getProperties().get("min");
            Integer max = (Integer)anno.getProperties().get("max");
            if(min!=null && max!=null){
                return "@Length(" + min + ", " + max + ")";
            }
            if(min!=null){
                return "@MinLength(" + min + ")";
            }
            return "@MaxLength(" + max + ")";
        });
    }

}