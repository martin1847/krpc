package com.bt.rpc.common.meta;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Property {
    /// name,may be null
    String name;

    /// type
    Dto type;

    List<Dto> generics;

    /// GetCustomAttributesData
    List<Anno> annotations;

    static final Set<String> REQUIRED_ANNO = new HashSet<>();

    static {
        REQUIRED_ANNO.add(NotNull.class.getSimpleName());
        REQUIRED_ANNO.add(NotBlank.class.getSimpleName());
        REQUIRED_ANNO.add(NotEmpty.class.getSimpleName());
    }

    public boolean isRequired() {
        return null != annotations && annotations.stream().anyMatch(it ->
                REQUIRED_ANNO.contains(it.name)
        );
    }

    //let list: Array<number> = [1, 2, 3];
    //List<int> Dart
}