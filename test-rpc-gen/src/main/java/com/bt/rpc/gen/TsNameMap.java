/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.gen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import com.bt.rpc.gen.meta.Anno;
import com.bt.rpc.gen.meta.PropertyType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import tech.krpc.annotation.Doc;

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

        nameMapping.put("Date","number");

        nameMapping.put("List","Array");
        nameMapping.put("Set","Array");
        nameMapping.put("Map","Record");
        nameMapping.put("String[]","string[]");
        nameMapping.put("byte[]","Array<number>");
        nameMapping.put("int[]","Array<number>");
    }

    final static Map<Class, BiFunction<Anno, PropertyType,String>> annoMapping = new HashMap<>();

    static {
        annoMapping.put(Doc.class, (it,t)-> "// "+String.valueOf(it.getProperties().get("value")));
        annoMapping.put(Deprecated.class,(it,t)->"Deprecated");
        annoMapping.put(Null.class, (it,t)->"@IsOptional()");
        annoMapping.put(NotNull.class, (it,t)->"@IsDefined()");
        annoMapping.put(NotEmpty.class, (it,t)->"@IsNotEmpty()");
        annoMapping.put(NotBlank.class, (it,t)->"@MinLength(1)");
        annoMapping.put(Positive.class, (it,t)->"@IsPositive()");
        annoMapping.put(Negative.class, (it,t)->"@IsNegative()");
        annoMapping.put(Email.class, (it,t)->"@IsEmail()");
        annoMapping.put(Min.class, (it,t)->"@Min("+it.getProperties().get("value")+")");
        annoMapping.put(Max.class, (it,t)->"@Max("+it.getProperties().get("value")+")");
        annoMapping.put(Size.class, (anno,t)->{
            Integer min = (Integer)anno.getProperties().get("min");
            Integer max = (Integer)anno.getProperties().get("max");
            var isStr = t.getRawType().getTypeName().equals("String");
            if(min!=null && max!=null){
                return isStr ? "@Length(" + min + ", " + max + ")" :
                        "@ArrayMinSize("+ min+")@ArrayMaxSize("+max+ ")";
            }
            if(min!=null){
                return (isStr ?"@MinLength(" :  "@ArrayMinSize(") + min + ")" ;
            }
            return (isStr ?"@MaxLength(" :  "@ArrayMaxSize(")  + max + ")";
        });
    }

}