/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc.gen;

import java.util.Map;
import java.util.function.BiFunction;

import com.btyx.rpc.gen.meta.Anno;
import com.btyx.rpc.gen.meta.PropertyType;
import com.google.common.base.Function;

/**
 *
 * @author Martin.C
 * @version 2021/12/31 2:45 PM
 */
public enum LangEnum {

    Typescript("typescript/DTO.ts","typescript/Service.ts",TsNameMap.nameMapping,TsNameMap.annoMapping),
    Miniprogram("typescript/DTO.ts","typescript/Service.ts",TsNameMap.nameMapping,TsNameMap.annoMapping),
    Yamltest("yamltest/DTO.yaml","yamltest/Service.yaml",TsNameMap.nameMapping,TsNameMap.annoMapping),
    Dart("dart/DTO.dart","dart/Service.dart",DartNameMap.nameMapping,DartNameMap.annoMapping);


    final String dto;

    final String serive;

    final NameRemapping remapping;

    final String fileExt;

    LangEnum(String dto, String serive, Map<String, String> nameMapping,
             Map<Class, BiFunction<Anno, PropertyType, String>> annoMapping) {
        this.dto = dto;
        this.serive = serive;
        this.remapping = new NameRemapping(nameMapping,annoMapping);
        this.fileExt = dto.substring(dto.indexOf('.'));
    }

    String dtoFileName(String app){
        return remapping.dtoFileName(app,this);
    }

    String serviceFileName(String service){
        return remapping.serviceFileName(service,this);
    }

}