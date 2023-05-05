package com.btyx.rpc.gen.meta;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.bt.rpc.annotation.Doc;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Property {
    /// name,may be null
    String name;

    /// type
    PropertyType type;

    /// GetCustomAttributesData
    List<Anno> annotations;

    static final Set<String> REQUIRED_ANNO = new HashSet<>();

    static {
        REQUIRED_ANNO.add(NotNull.class.getSimpleName());
        REQUIRED_ANNO.add(NotBlank.class.getSimpleName());
        REQUIRED_ANNO.add(NotEmpty.class.getSimpleName());
    }


    static final String DOC_ANNO = Doc.class.getSimpleName();

    public boolean isRequired() {
        return null != annotations && annotations.stream().anyMatch(it ->
                REQUIRED_ANNO.contains(it.originName)
        );
    }

    public boolean isHidden() {
        var hd = null != annotations && annotations.stream().anyMatch(it ->
                        DOC_ANNO.equals(it.originName) &&
                Boolean.TRUE.equals(it.properties.get("hidden"))
        );
        //System.out.println(name + " hidden : " + hd+" " + annotations);
        return hd;
    }

    //let list: Array<number> = [1, 2, 3];
    //List<int> Dart
}