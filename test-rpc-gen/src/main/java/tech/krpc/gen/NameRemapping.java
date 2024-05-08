/**
 * Martin.Cong
 * Copyright (c) 2021-2021 All Rights Reserved.
 */
package tech.krpc.gen;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import tech.krpc.gen.meta.Anno;
import tech.krpc.gen.meta.Dto;
import tech.krpc.gen.meta.PropertyType;

/**
 *
 * @author Martin.C
 * @version 2021/12/30 6:23 PM
 */
public class NameRemapping {

    final Map<String, String> nameMapping;

    final Map<String, BiFunction<Anno, PropertyType, String>> annoMapping;

    public NameRemapping(Map<String, String> nameMapping,
                         Map<Class, BiFunction<Anno,PropertyType, String>> annoMapping) {
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
                remappingAnnos(f.getAnnotations(),f.getType());
                var type = f.getType();
                if(null != type) {
                    remapping(type.getRawType());
                    remapping(type.getGenerics());
                }
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

    void remappingAnnos(List<Anno> annos,PropertyType type) {
        if (null == annos || annos.isEmpty()) {
            return;
        }
        for (var anno : annos) {
            var mapping = annoMapping.get(anno.originName);
            if (null != mapping) {
                anno.setName(mapping.apply(anno,type));
            } else {
                //default as a comment
                //System.out.println("find comment /// anno : "+ anno);
                anno.setName("/// " + anno.originName + anno.getProperties());
            }
        }
    }


    String dtoFileName(String app,LangEnum lang){
        if(app.charAt(0) == '-'){
            app = app.substring(1);
        }
        if(lang == LangEnum.Dart){
            app = app.replace('-','_')+"_dto";
        }else {
            app +="-dto";
        }
        return  app +  lang.fileExt;
    }


    String serviceFileName(String service,LangEnum lang){
        return service.toLowerCase(Locale.US)+ (lang == LangEnum.Dart? '_':'-')+"service"+lang.fileExt;
    }
}