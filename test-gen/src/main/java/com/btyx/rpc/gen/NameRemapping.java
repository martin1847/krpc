/**
 * Botaoyx.com Inc.
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package com.btyx.rpc.gen;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.btyx.rpc.gen.meta.Anno;
import com.btyx.rpc.gen.meta.Dto;
import com.btyx.rpc.gen.meta.PropertyType;
import com.google.common.base.Function;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 6:23 PM
 */
public class NameRemapping {

    final Map<String, String> nameMapping;

    final Map<String, Function<Anno, String>> annoMapping;

    public NameRemapping(Map<String, String> nameMapping,
                         Map<Class, Function<Anno, String>> annoMapping) {
        this.nameMapping = nameMapping;
        this.annoMapping = annoMapping.entrySet().stream().collect(
                Collectors.toMap(k -> k.getKey().getSimpleName(), Entry::getValue));
    }

    void remapping(Dto dto) {
        var newName = nameMapping.get(dto.getName());
        if (null != newName) {
            dto.setName(newName);
        }
        if (dto.hasChild()) {
            dto.getFields().forEach(f -> {
                remappingAnnos(f.getAnnotations());
                remapping(f.getType().getRawType());
                remapping(f.getType().getGenerics());
            });
        }
    }

    void remapping(PropertyType gen) {
        if (null == gen) {
            return;
        }
        remapping(gen.getRawType());
        remapping(gen.getGenerics());
    }

    void remapping(List<PropertyType> gens) {
        if (null == gens || gens.isEmpty()) {
            return;
        }
        gens.forEach(this::remapping);
    }

    void remappingAnnos(List<Anno> annos) {
        if (null == annos || annos.isEmpty()) {
            return;
        }
        for (var anno : annos) {
            var mapping = annoMapping.get(anno.originName);
            if (null != mapping) {
                anno.setName(mapping.apply(anno));
            } else {
                //default as a comment
                anno.setName("/// " + anno.originName + anno.getProperties());
            }
        }
    }


    String dtoFileName(String app,LangEnum lang){
        if(lang == LangEnum.Dart){
            app = app.replace('-','_');
        }
        return  app + lang.fileExt;
    }


    String serviceFileName(String service,LangEnum lang){
        return service.toLowerCase(Locale.US)+ (lang == LangEnum.Dart? '_':'-')+"service"+lang.fileExt;
    }
}