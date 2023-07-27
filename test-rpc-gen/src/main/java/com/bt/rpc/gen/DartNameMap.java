/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.bt.rpc.gen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import com.bt.rpc.annotation.Doc;
import com.bt.rpc.gen.meta.Anno;
import com.bt.rpc.gen.meta.PropertyType;

/**
 *
 * @author Martin.C
 * @version 2021/12/31 3:00 PM
 */
public class DartNameMap {

    final static Map<String,String> nameMapping = new HashMap<>();

    static {
        nameMapping.put("Integer","int");
        nameMapping.put("Long","int");
        nameMapping.put("Float","int");
        nameMapping.put("Double","int");
        nameMapping.put("Boolean","bool");
        //nameMapping.put("byte[]","List<int>");
        nameMapping.put("int[]","List<int>");
        nameMapping.put("String[]","List<String>");
        //   @JsonKey(fromJson: BaseService.jsonToU8, toJson: BaseService.u8ToJson)
        nameMapping.put("byte[]","Uint8List");
        nameMapping.put("Date","int");
        nameMapping.put("Object","dynamic");
    }

    final static Map<Class, BiFunction<Anno, PropertyType,String>> annoMapping = new HashMap<>();


    //https://pub.dev/documentation/validations/latest/
    // import 'package:validations/validations.dart';
    static {
        annoMapping.put(Doc.class, (it,t)->"/// "+it.getProperties().get("value"));
        annoMapping.put(Deprecated.class,(it,t)->"/// Deprecated");
        //annoMapping.put(Null.class, (it,t)->"@IsOptional()");
        //annoMapping.put(NotNull.class, (it,t)->"@IsDefined()");
        //annoMapping.put(NotEmpty.class, (it,t)->"@IsNotEmpty()");
        //annoMapping.put(NotBlank.class, (it,t)->"@MinLength(1)");
        //annoMapping.put(Positive.class, (it,t)->"@IsPositive()");
        //annoMapping.put(Negative.class, (it,t)->"@IsNegative()");
        //annoMapping.put(Email.class, (it,t)->"@IsEmail()");
        //annoMapping.put(Min.class, (it,t)->"@Min("+it.getProperties().get("value")+")");
        //annoMapping.put(Max.class, (it,t)->"@Max("+it.getProperties().get("value")+")");
        //annoMapping.put(Size.class, anno->{
        //    Integer min = (Integer)anno.getProperties().get("min");
        //    Integer max = (Integer)anno.getProperties().get("max");
        //    if(min!=null && max!=null){
        //        return "@Length(" + min + ", " + max + ")";
        //    }
        //    if(min!=null){
        //        return "@MinLength(" + min + ")";
        //    }
        //    return "@MaxLength(" + max + ")";
        //});
    }

}