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
public class DartNameMap {

    final static Map<String,String> nameMapping = new HashMap<>();

    static {
        nameMapping.put("Integer","int");
        nameMapping.put("Long","int");
        nameMapping.put("Float","int");
        nameMapping.put("Double","int");
        nameMapping.put("Boolean","bool");
        //nameMapping.put("byte[]","List<int>");
        //   @JsonKey(fromJson: BaseService.jsonToU8, toJson: BaseService.u8ToJson)
        nameMapping.put("byte[]","Uint8List");
        nameMapping.put("Date","int");
        nameMapping.put("Object","dynamic");
    }

    final static Map<Class, Function<Anno,String>> annoMapping = new HashMap<>();


    //https://pub.dev/documentation/validations/latest/
    // import 'package:validations/validations.dart';
    static {
        annoMapping.put(Doc.class, it->"/// "+it.getProperties().get("value"));
        annoMapping.put(Deprecated.class,it->"/// Deprecated");
        //annoMapping.put(Null.class, it->"@IsOptional()");
        //annoMapping.put(NotNull.class, it->"@IsDefined()");
        //annoMapping.put(NotEmpty.class, it->"@IsNotEmpty()");
        //annoMapping.put(NotBlank.class, it->"@MinLength(1)");
        //annoMapping.put(Positive.class, it->"@IsPositive()");
        //annoMapping.put(Negative.class, it->"@IsNegative()");
        //annoMapping.put(Email.class, it->"@IsEmail()");
        //annoMapping.put(Min.class, it->"@Min("+it.getProperties().get("value")+")");
        //annoMapping.put(Max.class, it->"@Max("+it.getProperties().get("value")+")");
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