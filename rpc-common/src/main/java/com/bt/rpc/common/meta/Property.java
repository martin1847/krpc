package com.bt.rpc.common.meta;

import com.bt.rpc.annotation.Doc;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
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

    @JsonIgnore
    public boolean isRequired() {
        return null != annotations && annotations.stream().anyMatch(it ->
                REQUIRED_ANNO.contains(it.name)
        );
    }
    @JsonIgnore
    public boolean isHidden() {
        return null != annotations && annotations.stream().anyMatch(it ->
                DOC_ANNO.equals(it.name) && Boolean.TRUE.equals(it.properties.get("hidden"))
        );
    }

    @JsonIgnore
    public String getDoc(){
        if(null == annotations){
            return "";
        }
        return annotations.stream().filter(it ->
                DOC_ANNO.equals(it.name)
        ).findFirst().map(it->"/// " +it.properties.get("value")).orElse("");
    }

    //let list: Array<number> = [1, 2, 3];
    //List<int> Dart
}