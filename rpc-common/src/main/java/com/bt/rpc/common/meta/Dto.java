package com.bt.rpc.common.meta;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 */

@Data
@NoArgsConstructor
@JsonInclude(Include.NON_NULL)
public class Dto {

    /// classSimpleName
    String name;

    int typeVar;

    // 是否做为入参
    boolean input;

    List<Property> fields;

    // 是否只是做为范型占位符
    boolean parameterized;
    //
    //public boolean hasChild() {
    //    return null != fields && !fields.isEmpty();
    //}

    public Dto(String name, int typeVar, boolean input) {
        this.name = name;
        this.typeVar = typeVar;
        this.input = input;
    }

    public Dto(String name, int typeVar, boolean input,boolean parameterized) {
        this.name = name;
        this.typeVar = typeVar;
        this.input = input;
        this.parameterized = parameterized;
    }
}